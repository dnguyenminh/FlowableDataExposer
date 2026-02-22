package vn.com.fecredit.flowable.exposer.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import vn.com.fecredit.flowable.exposer.entity.SysExposeRequest;
import vn.com.fecredit.flowable.exposer.repository.SysExposeRequestRepository;
import vn.com.fecredit.flowable.exposer.service.MetadataAnnotator;
import vn.com.fecredit.flowable.exposer.service.MetadataResolver;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Background worker responsible for consuming {@code SysExposeRequest}s
 * and rebuilding index/plain tables from the append-only case data store.
 */
@Component
@Profile("!test")
public class CaseDataWorker {

    private static final Logger log = LoggerFactory.getLogger(CaseDataWorker.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final MetadataAnnotator annotator;
    private final MetadataResolver resolver;
    private final SysExposeRequestRepository reqRepo;
    private final vn.com.fecredit.flowable.exposer.service.IndexLoader indexLoader;

    // Cache H2 detection result and common metadata to avoid repeated connection grabs (which can exhaust the pool)
    private Boolean cachedIsH2 = null;
    private volatile Set<String> cachedExistingTables = null; // uppercase table names
    private final Map<String, String> logicalToActualTableNames = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Set<String>> cachedTableColumns = new java.util.concurrent.ConcurrentHashMap<>();
    // Simple throttle to avoid rapid-fire metadata/DDL DB calls that can exhaust HikariCP in CI
    private final java.util.concurrent.Semaphore dbThrottle = new java.util.concurrent.Semaphore(12);

    public CaseDataWorker(JdbcTemplate jdbc,
                          ObjectMapper om,
                          MetadataAnnotator annotator,
                          MetadataResolver resolver,
                          SysExposeRequestRepository reqRepo,
                          vn.com.fecredit.flowable.exposer.service.IndexLoader indexLoader) {
        this.jdbc = jdbc;
        this.om = om;
        this.annotator = annotator;
        this.resolver = resolver;
        this.reqRepo = reqRepo;
        this.indexLoader = indexLoader;
    }

    @Scheduled(fixedDelay = 1000)
    public void pollAndProcess() {
        try {
            List<SysExposeRequest> pending = reqRepo.findByStatus("PENDING");
            if(pending == null || pending.isEmpty()) {
                log.debug("CaseDataWorker.pollAndProcess - no pending requests");
                return;
            }
            log.info("CaseDataWorker.pollAndProcess - found {} pending requests", pending.size());
            for (SysExposeRequest r : pending) {
                log.info("CaseDataWorker.pollAndProcess - processing request id={} caseInstanceId={}", r.getId(), r.getCaseInstanceId());
                try {
                    reindexByCaseInstanceId(r.getCaseInstanceId());
                    r.setStatus("DONE");
                    r.setProcessedAt(OffsetDateTime.now());
                    reqRepo.save(r);
                    log.info("CaseDataWorker.pollAndProcess - processed request id={} caseInstanceId={} -> DONE", r.getId(), r.getCaseInstanceId());
                } catch (Exception ex) {
                    r.setStatus("FAILED");
                    reqRepo.save(r);
                    log.error("Failed to process expose request {} for case {}", r.getId(), r.getCaseInstanceId(), ex);
                }
            }
        } catch (Exception ex) {
            log.error("CaseDataWorker.poll error", ex);
        }
    }

    @Transactional
    public void reindexByCaseInstanceId(String caseInstanceId) {
        log.info("reindexByCaseInstanceId - start caseInstanceId={}", caseInstanceId);
        try {
            Map<String, Object> row = fetchLatestRow(caseInstanceId);
            if (row == null) {
                log.info("No case data row found for {}", caseInstanceId);
                return;
            }

            String entityType = (String) row.get("entityType");
            String payload = (String) row.get("payload");
            Object rowCreatedAt = row.get("createdAt");
            if (entityType == null) entityType = "Order";

            Map<String, Object> vars = CaseDataWorkerHelpers.parsePayload(om, payload, caseInstanceId);

            try { annotator.annotate(vars, entityType); } catch (Exception ex) { log.debug("Annotator failed for case {}", caseInstanceId, ex); }

            String annotatedJson = om.writeValueAsString(vars);

            Map<String, MetadataDefinition.FieldMapping> mappings = resolver.mappingsMetadataFor(entityType);
            final Map<String, String> legacyMappings = resolver.mappingsFor(entityType);
            Map<String, MetadataDefinition.FieldMapping> effectiveMappings = computeEffectiveMappings(entityType, mappings);

            var directFallbacks = CaseDataWorkerHelpers.extractDirectFallbacks(annotatedJson);

            log.debug("reindexByCaseInstanceId - resolved entityType={}, mappings.count={}, legacy.count={}, directFallbacks.count={}",
                    entityType, mappings == null ? 0 : mappings.size(), legacyMappings == null ? 0 : legacyMappings.size(), directFallbacks == null ? 0 : directFallbacks.size());

            // Diagnostic: log case id and effective mapping keys
            try {
                System.out.println("DEBUG[CaseDataWorker]: processing caseInstanceId=" + caseInstanceId + " effectiveMappingsKeys=" + (effectiveMappings==null?"[]":effectiveMappings.keySet()));
            } catch (Exception ignored) {}

            upsertPlain(entityType, caseInstanceId, annotatedJson, rowCreatedAt, effectiveMappings, legacyMappings, directFallbacks);

            // New: process index mappings for the work class and nested objects
            try {
                // Process index definition targeted at the work class itself (e.g., Order)
                indexLoader.findByClass(entityType).ifPresent(def -> {
                    try { processIndexDefinition(def, caseInstanceId, annotatedJson, rowCreatedAt); } catch (Exception e) { log.error("processIndexDefinition failed: {}", e.getMessage(), e); }
                });

                // Also scan the annotated JSON for nested objects that match other index definitions (Item, Params, etc.)
                try {
                    // For each index definition not equal to the work class, try to locate candidate nodes
                    for (vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition other : indexLoader.all()) {
                        if (other == null) continue;
                        String keyClass = other._class != null ? other._class : other.workClassReference;
                        if (keyClass == null) continue;
                        // skip self
                        if (keyClass.equals(entityType) || (other.workClassReference != null && other.workClassReference.equals(entityType))) continue;

                        // If the index has an explicit root path other than "$", process against the full annotated JSON
                        if (other.jsonPath != null && !other.jsonPath.isBlank() && !"$".equals(other.jsonPath.trim())) {
                            try { processIndexDefinition(other, caseInstanceId, annotatedJson, rowCreatedAt); } catch (Exception ignored) {}
                            continue;
                        }

                        // Otherwise attempt to find nested nodes by checking the @class marker in the annotated JSON
                        try {
                            String expr = "$..[?(@['@class']=='" + keyClass + "')]";
                            Object matches = null;
                            try { matches = JsonPath.read(annotatedJson, expr); } catch (Exception jp) { matches = null; }
                            if (matches instanceof java.util.List) {
                            for (Object m : (java.util.List<?>) matches) {
                                try { processIndexDefinition(other, caseInstanceId, toJsonSafe(m), rowCreatedAt); } catch (Exception ignored) {}
                            }
                        }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}

            } catch (Exception ex) {
                log.error("Error processing index mappings for {}: {}", caseInstanceId, ex.getMessage(), ex);
            }

            log.info("reindexByCaseInstanceId - completed for {}", caseInstanceId);

        } catch (Exception ex) {
            log.error("reindex error for {}", caseInstanceId, ex);
        }
    }

    private Map<String, Object> fetchLatestRow(String caseInstanceId) {
        log.info("Querying latest sys_case_data_store row for caseInstanceId={}", caseInstanceId);
        try {
            // Use unquoted identifiers to be compatible with DATABASE_TO_LOWER=TRUE and standard H2/Postgres
            String sql = "SELECT entity_type, payload, created_at FROM sys_case_data_store WHERE case_instance_id = ? ORDER BY created_at DESC LIMIT 1";
            List<Map<String, Object>> rows = jdbc.queryForList(sql, caseInstanceId);
            
            if (rows.isEmpty()) {
                log.warn("fetchLatestRow: No row found for caseInstanceId={}", caseInstanceId);
                return null;
            }
            
            Map<String, Object> row = rows.get(0);
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("entityType", row.get("entity_type") != null ? row.get("entity_type") : row.get("ENTITY_TYPE"));
            result.put("payload", row.get("payload") != null ? row.get("payload") : row.get("PAYLOAD"));
            
            Object createdAt = row.get("created_at") != null ? row.get("created_at") : row.get("CREATED_AT");
            if (createdAt instanceof java.sql.Timestamp ts) {
                result.put("createdAt", ts.toInstant());
            } else if (createdAt instanceof java.time.Instant inst) {
                result.put("createdAt", inst);
            }
            return result;
        } catch (Exception ex) {
            log.error("fetchLatestRow failed for {}: {}", caseInstanceId, ex.getMessage(), ex);
            return null;
        }
    }

    private Map<String, MetadataDefinition.FieldMapping> computeEffectiveMappings(String entityType, Map<String, MetadataDefinition.FieldMapping> mappings) {
        if (mappings != null && !mappings.isEmpty()) return mappings;
        if (entityType == null) return Collections.emptyMap();
        String lower = entityType.toLowerCase();
        if (lower.endsWith("process")) {
            String base = entityType.substring(0, entityType.length() - "process".length());
            if (!base.isBlank()) {
                String cand = Character.toUpperCase(base.charAt(0)) + base.substring(1);
                try { return resolver.mappingsMetadataFor(cand); } catch (Exception ignored) {}
            }
        }
        return Collections.emptyMap();
    }

    private void upsertPlain(String entityType, String caseInstanceId, String annotatedJson, Object rowCreatedAt,
                             Map<String, MetadataDefinition.FieldMapping> effectiveMappings,
                             Map<String, String> legacyMappings, Map<String, Object> directFallbacks) {
        try {
            // Trace metadata resolution
            MetadataDefinition metaDef = resolver.resolveForClass(entityType);
            log.info("upsertPlain: resolver.resolveForClass({}) => {}", entityType, metaDef == null ? null : metaDef._class);
            if (metaDef != null) log.debug("upsertPlain: resolved metadata json: {}", om.writeValueAsString(metaDef));

            // Validate metadata schema and extract required fields for the entity type
            if (!validateWorkClassMetadataSchema(metaDef)) {
                log.warn("upsertPlain: metadata does not conform to Work Class Metadata Schema for case {} (entityType={})", caseInstanceId, entityType);
                return;
            }

            // Verify tableName is not empty
            if (metaDef.tableName == null || metaDef.tableName.trim().isEmpty()) {
                log.warn("upsertPlain: tableName is empty for case {}", caseInstanceId);
                return;
            }

            // Build row values from effective mappings and annotated JSON
            Map<String, Object> rowValues = buildRowValues(caseInstanceId, annotatedJson, rowCreatedAt, effectiveMappings, legacyMappings, directFallbacks);

            // Diagnostic logging: resolved mappings and final row values
            try {
                if (effectiveMappings != null && !effectiveMappings.isEmpty()) {
                    log.info("upsertPlain: effectiveMappings.count={}", effectiveMappings.size());
                    effectiveMappings.forEach((k, fm) -> {
                        try {
                            log.info("upsertPlain: mapping key={} jsonPath={} plainColumn={}", k, fm == null ? null : fm.jsonPath, fm == null ? null : fm.plainColumn);
                        } catch (Exception ignore) {
                        }
                    });
                }
                if (legacyMappings != null && !legacyMappings.isEmpty()) {
                    log.info("upsertPlain: legacyMappings.count={}", legacyMappings.size());
                    legacyMappings.forEach((k, v) -> log.info("upsertPlain: legacy mapping {} -> {}", k, v));
                }
                if (directFallbacks != null && !directFallbacks.isEmpty()) {
                    log.info("upsertPlain: directFallbacks.count={}", directFallbacks.size());
                }

                log.info("upsertPlain: final rowValues for case {} (table={}):", caseInstanceId, metaDef.tableName);
                rowValues.forEach((k, v) -> {
                    String type = v == null ? "null" : v.getClass().getSimpleName();
                    String sval = "null";
                    try {
                        if (v != null) {
                            sval = v instanceof String ? (v.toString().length() > 200 ? v.toString().substring(0, 200) + "..." : v.toString()) : v.toString();
                        }
                    } catch (Exception ignore) {
                    }
                    log.info("  column={} type={} value={}", k, type, sval);
                });
            } catch (Exception ex) {
                log.debug("upsertPlain: diagnostic logging failed: {}", ex.getMessage());
            }

            // Dynamically insert/upsert the row into the table specified by metadata
            upsertRowByMetadata(metaDef.tableName, rowValues);

            log.info("upsertPlain: Successfully upserted row for case {} into table {}", caseInstanceId, metaDef.tableName);
        } catch (Exception ex) {
            log.error("upsertPlain: Failed to upsert plain data for case {}", caseInstanceId, ex);
        }
    }

    /**
     * Validates that the metadata follows the Work Class Metadata Schema.
     * Required fields: class, tableName
     */
    private boolean validateWorkClassMetadataSchema(MetadataDefinition metaDef) {
        if (metaDef == null) {
            log.debug("validateWorkClassMetadataSchema: metadata is null");
            return false;
        }
        if (metaDef._class == null || metaDef._class.trim().isEmpty()) {
            log.debug("validateWorkClassMetadataSchema: 'class' field is missing or empty");
            return false;
        }
        if (metaDef.tableName == null || metaDef.tableName.trim().isEmpty()) {
            log.debug("validateWorkClassMetadataSchema: 'tableName' field is missing or empty");
            return false;
        }
        log.debug("validateWorkClassMetadataSchema: metadata validated for class {}", metaDef._class);
        return true;
    }

    /**
     * Builds a map of column names to values by extracting data from annotated JSON
     * according to the field mappings, with fallbacks to legacy mappings and direct fallbacks.
     */
    private Map<String, Object> buildRowValues(String caseInstanceId, String annotatedJson, Object rowCreatedAt,
                                                Map<String, MetadataDefinition.FieldMapping> effectiveMappings,
                                                Map<String, String> legacyMappings, Map<String, Object> directFallbacks) {
        Map<String, Object> rowValues = new java.util.LinkedHashMap<>();
        rowValues.put("case_instance_id", caseInstanceId);

        // Process effective field mappings
        if (effectiveMappings != null && !effectiveMappings.isEmpty()) {
            for (var entry : effectiveMappings.entrySet()) {
                MetadataDefinition.FieldMapping fm = entry.getValue();
                if (fm.jsonPath == null) continue;

                try {
                    // extract the raw value using JsonPath and prepare working copy
                    Object extractedValue = JsonPath.read(annotatedJson, fm.jsonPath);
                    Object valueToPut = extractedValue;

                    // Fix: check for empty collections or empty JSON strings
                    if (isEmptyResult(valueToPut)) {
                        valueToPut = null;
                    }

                    // Convert complex JSON structures (maps/arrays) into a JSON string for DB persistence
                    if (valueToPut != null
                            && !(valueToPut instanceof String)
                            && !(valueToPut instanceof Number)
                            && !(valueToPut instanceof Boolean)
                            && !(valueToPut instanceof java.time.temporal.Temporal)
                            && !(valueToPut instanceof java.util.Date)) {
                        try {
                            valueToPut = om.writeValueAsString(valueToPut);
                        } catch (Exception se) {
                            // fallback to toString()
                            valueToPut = valueToPut.toString();
                        }
                    }

                    // Use plainColumn if specified, otherwise fall back to column name
                    String columnName = fm.plainColumn != null && !fm.plainColumn.trim().isEmpty()
                            ? fm.plainColumn
                            : fm.column;
                    if (columnName != null && !columnName.trim().isEmpty()) {
                        rowValues.put(columnName, valueToPut);
                        log.debug("buildRowValues: mapped jsonPath {} -> column {} (value type={})", fm.jsonPath, columnName, valueToPut == null ? "null" : valueToPut.getClass().getSimpleName());
                    }
                } catch (Exception ex) {
                    log.debug("Failed to extract value for column {} using jsonPath {}: {}", entry.getKey(), fm.jsonPath, ex.getMessage());
                }
            }
        }

        // Apply legacy fallback mappings
        if (legacyMappings != null && !legacyMappings.isEmpty()) {
            for (var entry : legacyMappings.entrySet()) {
                String columnName = entry.getKey();
                String jsonPath = entry.getValue();
                if (rowValues.containsKey(columnName)) continue; // skip if already set
                try {
                    Object extractedValue = JsonPath.read(annotatedJson, jsonPath);
                    if (extractedValue != null) {
                        rowValues.put(columnName, extractedValue);
                    }
               
               
                } catch (Exception ex) {
                    log.debug("Failed to extract legacy value for column {} using jsonPath {}: {}", columnName, jsonPath, ex.getMessage());
                }
            }
        }

        // Apply direct fallback mappings
        if (directFallbacks != null && !directFallbacks.isEmpty()) {
            directFallbacks.forEach((key, value) -> {
                if (value != null && !rowValues.containsKey(key)) {
                    rowValues.put(key, value);
                }
            });
        }

        // Ensure plain_payload contains the full annotated JSON when not explicitly set by mappings
        if (!rowValues.containsKey("plain_payload")) {
            rowValues.put("plain_payload", annotatedJson);
        }

        // Set created_at timestamp if provided and not already set
        if (rowCreatedAt != null && !rowValues.containsKey("created_at")) {
            rowValues.put("created_at", rowCreatedAt);
        }

        log.debug("buildRowValues: extracted {} columns for case {}", rowValues.size(), caseInstanceId);
        return rowValues;
    }

    // ---------- Index processing helpers ----------
    /**
     * Processes index definitions for a case by extracting data via JsonPath and upserting to index tables.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Read the index's root JsonPath (default "$" for the entire object)</li>
     *   <li>Extract the data at that path from the annotated case JSON</li>
     *   <li>Based on extracted data type:
     *       <ul>
     *         <li><strong>List</strong>: emit one index row per array element (array expansion)</li>
     *         <li><strong>Map</strong>: emit one index row per key-value entry (map expansion) — entries wrapped as {_key, _value}</li>
     *         <li><strong>Single Object</strong>: emit one index row</li>
     *       </ul>
     *   </li>
     *   <li>For each row, call {@link #buildIndexRow(IndexDefinition, String, String)} to map individual fields via IndexField definitions</li>
     *   <li>Batch-upsert all rows to the index table via {@link #upsertRowsByMetadata(String, List, IndexDefinition)}</li>
     * </ol>
     *
     * <p>Index Mapping Type:
     * <ul>
     *   <li>IndexDefinition contains the target table name (e.g., "item_index"), root JsonPath, and a list of IndexField mappings</li>
     *   <li>Each IndexField specifies: jsonPath (location in item JSON), plainColumn (output column name), and type (SQL type hint)</li>
     *   <li>Type hints (e.g., "DECIMAL(10,2)", "BIGINT") are passed to {@link #determineColumnType(Object, String)} for column creation</li>
     * </ul>
     *
     * <p>Array Expansion Example:
     * <pre>
     * IndexDefinition: table="item_index", jsonPath="$.items", mappings=[IndexField(jsonPath="$.id", plainColumn="item_id", type="VARCHAR(50)")]
     * Input JSON: { "caseInstanceId": "C1", "items": [ { "id": "1", "sku": "A" }, { "id": "2", "sku": "B" } ] }
     * Result: Two rows inserted into item_index table
     *   Row 1: case_instance_id="C1", item_id="1", plain_payload={"id":"1","sku":"A"}
     *   Row 2: case_instance_id="C1", item_id="2", plain_payload={"id":"2","sku":"B"}
     * </pre>
     *
     * <p>Map Expansion Example:
     * <pre>
     * IndexDefinition: table="attr_index", jsonPath="$.attributes", mappings=[IndexField(jsonPath="$._key", plainColumn="attr_name", type="VARCHAR(100)"), IndexField(jsonPath="$._value", plainColumn="attr_value", type="LONGTEXT")]
     * Input JSON: { "caseInstanceId": "C1", "attributes": { "color": "blue", "size": "XL" } }
     * Result: Two rows inserted into attr_index table (one per map entry)
     *   Row 1: case_instance_id="C1", attr_name="color", attr_value="blue", plain_payload={"_key":"color","_value":"blue"}
     *   Row 2: case_instance_id="C1", attr_name="size", attr_value="XL", plain_payload={"_key":"size","_value":"XL"}
     * </pre>
     *
     * <p>Error Handling: Failures are logged at ERROR level but do not propagate, allowing the listener to continue processing.
     *
     * @param def the {@link vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition} containing:
     *            - table: target index table name (e.g., "item_index", "attr_index")
     *            - jsonPath: root extraction path (default "$"); can extract List, Map, or single Object
     *            - mappings: list of IndexField definitions for column extraction; for maps use $_key and $_value access patterns
     * @param caseInstanceId the case instance ID to include in all index rows (for cross-referencing)
     * @param annotatedJson the full case payload JSON (decrypted blob + metadata annotations)
     */
    private void processIndexDefinition(vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition def, String caseInstanceId, String annotatedJson) {
        processIndexDefinition(def, caseInstanceId, annotatedJson, null);
    }

    private void processIndexDefinition(vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition def, String caseInstanceId, String annotatedJson, Object rowCreatedAt) {
         if (def == null || def.mappings == null || def.mappings.isEmpty()) return;
         try {
             String rootPath = def.jsonPath == null || def.jsonPath.isBlank() ? "$" : def.jsonPath;
             Object extracted = null;
             try {
                 extracted = JsonPath.read(annotatedJson, rootPath);
             } catch (Exception ignored) {
                 // try plural/singular/bracketed alternates when declared root doesn't match payload
                 try { extracted = tryAlternateRoots(annotatedJson, rootPath); } catch (Exception ignored2) {}
             }
             if (extracted == null) {
                 // scan for nodes annotated with @class matching this index's class as a fallback
                 try {
                     if (def._class != null && !def._class.isBlank()) {
                         String expr = "$..[?(@['@class']=='" + def._class + "')]";
                         Object matches = null;
                         try { matches = JsonPath.read(annotatedJson, expr); } catch (Exception jp) { matches = null; }
                         if (matches instanceof java.util.List && !((java.util.List<?>) matches).isEmpty()) {
                             extracted = matches;
                         }
                     }
                 } catch (Exception ignored3) {}

                 // Special-case: some metadata maps rules under different keys; try deep search for 'rules' when declared jsonPath references rules
                 try {
                     if (extracted == null && def.jsonPath != null && def.jsonPath.contains("rules")) {
                         Object deep = null;
                         try { deep = JsonPath.read(annotatedJson, "$..rules"); } catch (Exception ignored4) { deep = null; }
                         if (deep != null) extracted = deep;
                     }
                 } catch (Exception ignored5) {}
             }
             List<Map<String, Object>> rows = new java.util.ArrayList<>();

             // Heuristic: only expand a Map into {_key,_value} entries when the index mappings reference those keys.
             boolean expandMapEntries = true;
             if ("$".equals(rootPath)) {
                 expandMapEntries = false;
                 for (vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition.IndexField f : def.mappings) {
                     if (f.jsonPath != null && (f.jsonPath.contains("_key") || f.jsonPath.contains("_value") || f.jsonPath.contains("$._key") || f.jsonPath.contains("$._value"))) {
                         expandMapEntries = true;
                         break;
                     }
                 }
             }

             if (extracted instanceof java.util.List) {
             // List expansion: emit one row per array element
             List<?> items = (List<?>) extracted;
             for (Object item : items) {
                 String jsonForItem = toJsonSafe(item);
                 Map<String, Object> row = buildIndexRow(def, caseInstanceId, jsonForItem, rowCreatedAt);
                 rows.add(row);
             }
             } else if (extracted instanceof java.util.Map) {
                 Map<?, ?> mapEntries = (Map<?, ?>) extracted;
                 if (expandMapEntries) {
                     // Map expansion: emit one row per map entry (key-value pair). Skip metadata keys starting with '@'.
                     for (Map.Entry<?, ?> entry : mapEntries.entrySet()) {
                         Object k = entry.getKey();
                         if (k instanceof String && ((String) k).startsWith("@")) {
                             log.debug("processIndexDefinition: skipping metadata map key {} for table {}", k, def.table);
                             continue;
                         }
                         java.util.Map<String, Object> entryMap = new java.util.HashMap<>();
                         entryMap.put("_key", entry.getKey());
                         entryMap.put("_value", entry.getValue());
                         String jsonForItem = toJsonSafe(entryMap);
                         Map<String, Object> row = buildIndexRow(def, caseInstanceId, jsonForItem, rowCreatedAt);
                         rows.add(row);
                         log.debug("processIndexDefinition: expanded map entry key={} for table {}", entry.getKey(), def.table);
                     }
                 } else {
                     // Treat the entire map as a single object (preserve paths like $.customer.id)
                     String jsonForItem = toJsonSafe(extracted);
                     Map<String, Object> row = buildIndexRow(def, caseInstanceId, jsonForItem, rowCreatedAt);
                     rows.add(row);
                 }
             } else {
                 // Single object: emit one row
                 String jsonForItem = toJsonSafe(extracted);
                 Map<String, Object> row = buildIndexRow(def, caseInstanceId, jsonForItem, rowCreatedAt);
                 rows.add(row);
             }
             if (!rows.isEmpty()) upsertRowsByMetadata(def.table, rows, def);
         } catch (Exception ex) {
             log.error("processIndexDefinition failed for case {} table {}: {}", caseInstanceId, def.table, ex.getMessage(), ex);
         }
     }

    // Helper: try alternate root paths for failing index definitions (plural/singular/bracketed)
    private Object tryAlternateRoots(String annotatedJson, String rootPath) {
        if (rootPath == null || annotatedJson == null) return null;
        try {
            java.util.List<String> alts = new java.util.ArrayList<>();
            if (rootPath.startsWith("$.")) {
                String body = rootPath.substring(2);
                alts.add("$['" + body + "']");
                // simple dotted form
                alts.add("$." + body);
                // singular/plural variants
                if (body.endsWith("s") && body.length()>1) alts.add("$." + body.substring(0, body.length()-1));
                else alts.add("$." + body + "s");
            } else if (rootPath.startsWith("$['") && rootPath.endsWith("']")) {
                String key = rootPath.substring(3, rootPath.length()-2);
                alts.add("$." + key);
                if (key.endsWith("s") && key.length()>1) alts.add("$." + key.substring(0, key.length()-1));
                else alts.add("$." + key + "s");
            }
            // Also try bracketed form
            if (!rootPath.startsWith("$['") && rootPath.startsWith("$.")) {
                String body = rootPath.substring(2);
                alts.add("$['" + body + "']");
            }
            for (String a : alts) {
                try { Object r = JsonPath.read(annotatedJson, a); if (r != null) return r; } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String toJsonSafe(Object obj) {
        try { return om.writeValueAsString(obj); } catch (Exception e) { return obj == null ? "null" : obj.toString(); }
    }

    /**
     * Builds a single index row by extracting field values from a JSON object according to field mappings.
     *
     * <p>For each {@link IndexDefinition.IndexField} in the index definition:
     * <ol>
     *   <li>Apply the field's JsonPath expression to the item JSON</li>
     *   <li>If the extracted value is a complex type (Map, List, etc.), serialize it to JSON string</li>
     *   <li>Use the field's {@code plainColumn} name if specified, otherwise derive from JsonPath</li>
     *   <li>Add the column-value pair to the result map</li>
     * </ol>
     *
     * <p>Every row includes:
     * <ul>
     *   <li>{@code case_instance_id}: The parent case ID for cross-referencing</li>
     *   <li>{@code plain_payload}: The full JSON representation of this item (for audit/debugging)</li>
     *   <li>All mapped fields as extracted via JsonPath</li>
     * </ul>
     *
     * <p>Example:
     * <pre>
     * IndexField: jsonPath="$.price" plainColumn="item_price"
     * Item JSON: { "name": "Widget", "price": 99.99 }
     * Result: { "case_instance_id": "C1", "plain_payload": {...}, "item_price": 99.99 }
     * </pre>
     *
     * <p>Failures to extract individual fields are logged but do not halt row building.
     *
     * @param def the IndexDefinition containing field mappings
     * @param caseInstanceId the case instance ID to include in the row
     * @param jsonForItem the JSON string representation of a single item (may be a list element or the root object)
     * @return a Map of column names to values ready for upsert
     */
    // Backwards-compatible overload: keep original 3-arg signature used elsewhere
    private Map<String, Object> buildIndexRow(vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition def, String caseInstanceId, String jsonForItem) {
        return buildIndexRow(def, caseInstanceId, jsonForItem, null);
    }

    private Map<String, Object> buildIndexRow(vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition def, String caseInstanceId, String jsonForItem, Object rowCreatedAt) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("case_instance_id", caseInstanceId);
        row.put("plain_payload", jsonForItem);
        for (vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition.IndexField f : def.mappings) {
            Object val = null;
            String usedPath = null;
            try {
                String rawPath = f.jsonPath == null ? "$" : f.jsonPath.trim();
                java.util.List<String> candidates = new java.util.ArrayList<>();
                // Always try the declared path first
                candidates.add(rawPath);
                // Support shorthand "$_key" / "$_value" -> "$._key" / "$._value"
                if (rawPath.startsWith("$_")) {
                    candidates.add("$." + rawPath.substring(1));
                    candidates.add("$['" + rawPath.substring(1) + "']");
                }
                // Support $._value wrapper forms when mappings point into inner-object
                if (rawPath.startsWith("$.") ) {
                    candidates.add("$._value" + rawPath.substring(1));
                    // add bracketed form e.g. $.a.b -> $['a']['b']
                    try {
                        String[] parts = rawPath.substring(2).split("\\.");
                        StringBuilder b = new StringBuilder("$");
                        for (String p : parts) b.append("['").append(p).append("']");
                        candidates.add(b.toString());
                    } catch (Exception ignored) {}
                }

                // Add recursive-decent fallback for leaf names (e.g. $.customer.name -> $..name) and rules deep scans
                try {
                    if (rawPath != null && rawPath.startsWith("$.") && rawPath.contains(".")) {
                        String leaf = rawPath.substring(rawPath.lastIndexOf('.') + 1);
                        if (leaf != null && !leaf.isBlank()) candidates.add("$.." + leaf);
                    }
                    if (rawPath != null && rawPath.toLowerCase().contains("rules")) {
                        candidates.add("$..rules");
                        candidates.add("$..rule");
                        candidates.add("$..discount");
                    }
                } catch (Exception ignored) {}

                // Try each candidate against the raw JSON string first
                for (String p : candidates) {
                    try {
                        Object got = JsonPath.read(jsonForItem, p);
                        if (got != null) { val = got; usedPath = p; break; }
                    } catch (Exception ignored) {
                    }
                }

                // If still null, try parsing the JSON to an object and re-apply JsonPath candidates
                if (val == null && jsonForItem != null) {
                    try {
                        Object parsed = om.readValue(jsonForItem, Object.class);
                        for (String p : candidates) {
                            try {
                                Object got = JsonPath.read(parsed, p);
                                if (got != null) { val = got; usedPath = "(parsed)" + p; break; }
                            } catch (Exception ignored) {}
                        }
                        // As an additional fallback, try simple map traversal for $.a.b style
                        if (val == null && rawPath.startsWith("$.") && parsed instanceof java.util.Map) {
                            Object cur = parsed;
                            String[] parts = rawPath.substring(2).split("\\.");
                            for (String p : parts) {
                                if (cur instanceof java.util.Map) cur = ((java.util.Map<?, ?>) cur).get(p);
                                else { cur = null; break; }
                            }
                            if (cur != null) { val = cur; usedPath = "(parsedSimple)" + rawPath; }
                        }

                        // Extra parsed-map heuristics for name fields: case-insensitive and common variants
                        if (val == null && rawPath.endsWith(".name") && rawPath.startsWith("$.") && parsed instanceof java.util.Map) {
                            try {
                                String[] parts = rawPath.substring(2).split("\\.");
                                // navigate to the parent object of the 'name' field
                                Object cur = parsed;
                                for (int i = 0; i < parts.length - 1; i++) {
                                    if (cur instanceof java.util.Map) cur = ((java.util.Map<?, ?>) cur).get(parts[i]); else { cur = null; break; }
                                }
                                if (cur instanceof java.util.Map) {
                                    java.util.Map<?,?> m = (java.util.Map<?,?>) cur;
                                    java.util.List<String> variants = java.util.Arrays.asList("name","fullName","full_name","displayName","display_name","fullname","name", "Name");
                                    // include root-based variants like customerName / customer_name
                                    String root = parts.length>1 ? parts[parts.length-2] : null;
                                    if (root != null) {
                                        variants = new java.util.ArrayList<>(variants);
                                        variants.add(root + "Name");
                                        variants.add(root + "_name");
                                    }
                                    for (String k : variants) {
                                        for (Object keyObj : m.keySet()) {
                                            if (keyObj == null) continue;
                                            String key = keyObj.toString();
                                            if (key.equalsIgnoreCase(k)) {
                                                Object got = m.get(keyObj);
                                                if (got != null) { val = got; usedPath = "(ciParsed)" + key; break; }
                                            }
                                        }
                                        if (val != null) break;
                                    }
                                }
                            } catch (Exception ignored) {}
                        }

                        // If mapping references rules and we still have a parsed object, attempt to deep-scan for discounts
                        if (val == null && parsed instanceof java.util.Map && rawPath != null && rawPath.toLowerCase().contains("rule")) {
                            try {
                                java.util.List<Object> found = new java.util.ArrayList<>();
                                // simple recursive scan for any map containing 'discount'
                                java.util.function.BiConsumer<java.util.Map<?,?>,String> scan = new java.util.function.BiConsumer<java.util.Map<?,?>,String>(){
                                    public void accept(java.util.Map<?,?> m, String path) {
                                        for (Object k : m.keySet()) {
                                            Object v = m.get(k);
                                            String key = k==null?"":k.toString();
                                            if ("discount".equalsIgnoreCase(key) || key.toLowerCase().contains("discount")) found.add(v);
                                            if (v instanceof java.util.Map) this.accept((java.util.Map<?,?>)v, path + "/" + key);
                                            if (v instanceof java.util.List) for (Object el : (java.util.List<?>)v) if (el instanceof java.util.Map) this.accept((java.util.Map<?,?>)el, path + "/" + key);
                                        }
                                    }
                                };
                                scan.accept((java.util.Map<?,?>) parsed, "$.");
                                if (!found.isEmpty()) { val = found.get(0); usedPath = "(deepScan)discount"; }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }

                // Name-based pragmatic fallbacks for common legacy names
                if (val == null) {
                    try {
                        if ("$.requestedBy".equalsIgnoreCase(rawPath) || "$.requested_by".equalsIgnoreCase(rawPath)) {
                            try { val = JsonPath.read(jsonForItem, "$.initiator"); usedPath = "$.initiator"; } catch (Exception ignored) {}
                            // also try root-level 'initiator' boolean/object variants
                            if (val == null) {
                                try { val = JsonPath.read(jsonForItem, "$.requestedBy"); usedPath = "$.requestedBy"; } catch (Exception ignored) {}
                            }
                        }
                        if (val == null && "$.createTime".equalsIgnoreCase(rawPath)) {
                            try { val = JsonPath.read(jsonForItem, "$.createdAt"); usedPath = "$.createdAt"; } catch (Exception ignored) {}
                            if (val == null) try { val = JsonPath.read(jsonForItem, "$.created_at"); usedPath = "$.created_at"; } catch (Exception ignored) {}
                            // fallback to injected rowCreatedAt if available
                            if (val == null && rowCreatedAt != null) { val = rowCreatedAt; usedPath = "(injected)rowCreatedAt"; }
                            // if still null, try inner object e.g. $.meta.createTime
                            if (val == null) {
                                try { val = JsonPath.read(jsonForItem, "$.meta.createTime"); usedPath = "$.meta.createTime"; } catch (Exception ignored) {}
                            }
                        }

                        if (val == null && rawPath.endsWith(".name")) {
                            // try $.customerName and snake_case and other common variants
                            try {
                                String root = rawPath.startsWith("$.") ? rawPath.substring(2, rawPath.length() - 5) : null;
                                if (root != null) {
                                    java.util.List<String> tryPaths = new java.util.ArrayList<>();
                                    tryPaths.add("$." + root + "Name");
                                    tryPaths.add("$." + root + "_name");
                                    tryPaths.add("$." + root + ".name");
                                    tryPaths.add("$." + root + ".fullName");
                                    tryPaths.add("$." + root + ".displayName");
                                    tryPaths.add("$..name");
                                    for (String p : tryPaths) {
                                        try { val = JsonPath.read(jsonForItem, p); if (val != null) { usedPath = p; break; } } catch (Exception ignored) {}
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }

                // Serialize complex types
                Object putVal = val;
                if (putVal != null && !(putVal instanceof String) && !(putVal instanceof Number) && !(putVal instanceof Boolean)
                        && !(putVal instanceof java.time.temporal.Temporal) && !(putVal instanceof java.util.Date)) {
                    try { putVal = om.writeValueAsString(putVal); } catch (Exception ex) { putVal = putVal.toString(); }
                }

                String col = f.plainColumn != null && !f.plainColumn.isBlank() ? f.plainColumn : f.jsonPath.replaceAll("[^a-zA-Z0-9_]", "_");
                row.put(col, putVal);

                // Diagnostic: print per-field extraction to help CI-captured logs
                try {
                    String rawType = putVal == null ? "null" : putVal.getClass().getName();
                    String rawSerialized;
                    try { rawSerialized = putVal == null ? "null" : (putVal instanceof String ? (String) putVal : om.writeValueAsString(putVal)); } catch (Exception ex) { rawSerialized = putVal == null ? "null" : putVal.toString(); }
                    String preview = rawSerialized;
                    System.out.println("DIAG[buildIndexRow]: table=" + def.table + " path=" + f.jsonPath + " usedPath=" + (usedPath == null ? "-" : usedPath) + " col=" + col + " rawType=" + rawType + " rawSerialized=" + rawSerialized + " storedPreview=" + preview);
                } catch (Exception ignored) {}

            } catch (Exception ex) {
                log.debug("Failed to extract index field {} for table {}: {}", f.jsonPath, def.table, ex.getMessage());
            }
        }

        // Ensure created_at exists using injected rowCreatedAt if available and mapping didn't provide it
        try {
            if (!row.containsKey("created_at") && rowCreatedAt != null) {
                row.put("created_at", rowCreatedAt);
            }
        } catch (Exception ignored) {}

        return row;
    }

    /**
     * Dynamically inserts or updates a row in the specified table.
     */
    private void upsertRowByMetadata(String tableName, Map<String, Object> rowValues) {
        if (tableName == null || tableName.trim().isEmpty() || rowValues.isEmpty()) {
            log.warn("upsertRowByMetadata: invalid arguments - tableName={}, rowCount={}", tableName, rowValues.size());
            return;
        }

        try {
            // Validate table name to prevent SQL injection
            if (!isValidIdentifier(tableName)) {
                log.error("upsertRowByMetadata: invalid table name: {}", tableName);
                return;
            }

            // Check if table exists, if not create it with default schema
            if (!tableExists(tableName)) {
                log.info("upsertRowByMetadata: table {} does not exist, creating with default schema", tableName);
                createDefaultWorkTable(tableName, rowValues);
                log.info("upsertRowByMetadata: successfully created table {}", tableName);
            }

            // Resolve actual table name from DB (handles case-sensitivity issues)
            String actualTable = resolveActualTableName(tableName);

            // Ensure all columns referenced in rowValues exist before attempting insert
            ensureColumnsPresent(actualTable, rowValues, null);

            // Build deterministic column order and dynamic upsert SQL
            java.util.List<String> colOrder = upsertColumnOrder(rowValues);
            String upsertSql = buildUpsertSql(actualTable, colOrder);
            log.debug("upsertRowByMetadata: executing SQL for table {} with {} columns", actualTable, colOrder.size());

            // Execute the upsert with parameterized values (align params with column order)
            Object[] paramValues = colOrder.stream().map(rowValues::get).toArray();
            try {
                // For H2, buildUpsertSql returns a special marker to indicate explicit select/update/insert should be used
                if ("__H2_SELECT_UPDATE_INSERT__".equals(upsertSql) && isH2()) {
                    System.out.println("SQL-INSTRUMENT: Routing to H2 fallback for table=" + actualTable + " params=" + java.util.Arrays.toString(paramValues));
                    h2SelectUpdateInsert(actualTable, colOrder, rowValues);
                } else {
                    System.out.println("SQL-INSTRUMENT: Executing upsert SQL: " + upsertSql + " params=" + java.util.Arrays.toString(paramValues));
                    jdbc.update(upsertSql, paramValues);
                }
            } catch (org.springframework.jdbc.BadSqlGrammarException badSql) {
                // Fallback for H2 where MERGE or complex upsert SQLs can be flaky
                if (isH2()) {
                    try {
                        log.info("upsertRowByMetadata: falling back to SELECT→UPDATE/INSERT for H2 on table {}", actualTable);
                        h2SelectUpdateInsert(actualTable, colOrder, rowValues);
                        return;
                    } catch (Exception hx) {
                        log.error("upsertRowByMetadata: H2 fallback failed: {}", hx.getMessage(), hx);
                    }
                }
                throw badSql;
            }

            log.info("upsertRowByMetadata: successfully upserted row into {}", actualTable);
        } catch (Exception ex) {
            log.error("upsertRowByMetadata: failed to upsert into table {}: {}", tableName, ex.getMessage(), ex);
        }
    }

    /**
     * Batch-upserts multiple rows into an index table with type hints from the index definition.
     */
    private void upsertRowsByMetadata(String tableName, java.util.List<Map<String, Object>> rows, vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition def) {
        if (rows == null || rows.isEmpty()) return;
        try {
            // Build type hints from IndexDefinition mappings
            Map<String, String> hints = new java.util.HashMap<>();
            if (def != null && def.mappings != null) {
                for (vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition.IndexField f : def.mappings) {
                    String col = f.plainColumn != null && !f.plainColumn.isBlank() ? f.plainColumn : f.jsonPath.replaceAll("[^a-zA-Z0-9_]", "_");
                    if (f.type != null && !f.type.isBlank()) hints.put(col, f.type);
                }
            }

            // Ensure table exists before processing rows
            if (!tableExists(tableName)) {
                log.info("upsertRowsByMetadata: table {} does not exist, creating with default schema", tableName);
                createDefaultWorkTable(tableName, rows.get(0));
            }

            String actualTable = resolveActualTableName(tableName);

            for (Map<String, Object> row : rows) {
                ensureColumnsPresent(actualTable, row, hints);
            }

            // Simple batch execution: execute upsert per row in a loop
            for (Map<String, Object> row : rows) {
                java.util.List<String> columnOrder = upsertColumnOrder(row);
                java.util.List<Object> paramsList = new java.util.ArrayList<>();
                for (String col : columnOrder) {
                    Object val = row.get(col);
                    if (isEmptyResult(val)) val = null;
                    paramsList.add(val);
                }
                if (paramsList.isEmpty()) continue;
                String upsertSql = buildUpsertSql(actualTable, row);
                Object[] params = paramsList.toArray();
                try {
                    if ("__H2_SELECT_UPDATE_INSERT__".equals(upsertSql) && isH2()) {
                        h2SelectUpdateInsert(actualTable, columnOrder, row);
                    } else {
                        jdbc.update(upsertSql, params);
                     }
                 } catch (org.springframework.jdbc.BadSqlGrammarException badSql) {
                    log.error("upsertRowsByMetadata: bad SQL grammar for table {}: {}", actualTable, badSql.getMessage());
                }
            }
            log.info("upsertRowsByMetadata: upserted {} rows into {}", rows.size(), actualTable);
        } catch (Exception ex) {
            log.error("upsertRowsByMetadata: failed for table {}: {}", tableName, ex.getMessage(), ex);
        }
    }

    private String resolveActualTableName(String logicalName) {
        if (logicalName == null) return null;
        String up = logicalName.toUpperCase(java.util.Locale.ROOT);
        String actual = logicalToActualTableNames.get(up);
        return actual != null ? actual : logicalName;
    }

    /**
     * Checks if a table exists in the database.
     * Handles database-specific metadata queries gracefully and is case-insensitive.
     */
    private boolean tableExists(String tableName) {
        if (tableName == null) return false;
        String up = tableName.toUpperCase(java.util.Locale.ROOT);
        // Check cached table set first
        Set<String> localCached = cachedExistingTables;
        if (localCached != null && localCached.contains(up)) {
            log.debug("tableExists (cache): table {} exists = true", tableName);
            return true;
        }
        try {
            java.sql.Connection conn = null;
                boolean permitAcquired = false;
                try {
                    try { permitAcquired = dbThrottle.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    if (!permitAcquired) log.warn("tableExists: dbThrottle permit not acquired, proceeding");
                    try {
                        conn = jdbc.getDataSource() == null ? null : jdbc.getDataSource().getConnection();
                        if (conn != null) {
                            // Try exact match first
                            try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, null, up, new String[]{"TABLE"})) {
                                if (rs.next()) {
                                    String found = rs.getString("TABLE_NAME");
                                    logicalToActualTableNames.put(up, found);
                                    if (cachedExistingTables == null) cachedExistingTables = new java.util.HashSet<>();
                                    cachedExistingTables.add(up);
                                    return true;
                                }
                            }
                            // Fallback: search all tables case-insensitively (supports DATABASE_TO_LOWER=TRUE)
                            try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, null, null, new String[]{"TABLE"})) {
                                while (rs.next()) {
                                    String foundName = rs.getString("TABLE_NAME");
                                    if (up.equalsIgnoreCase(foundName)) {
                                        logicalToActualTableNames.put(up, foundName);
                                        if (cachedExistingTables == null) cachedExistingTables = new java.util.HashSet<>();
                                        cachedExistingTables.add(up);
                                        return true;
                                    }
                                }
                            }
                        }
                    } finally {
                        if (conn != null) try { conn.close(); } catch (Exception ignored) {}
                    }
                } finally {
                    if (permitAcquired) dbThrottle.release();
                }
        } catch (Exception ex) {
            log.debug("tableExists: metadata lookup failed for {}: {}", tableName, ex.getMessage());
        }
        // Fallback: lightweight query (non-throwing)
        try {
            // Use safeQuote table name for the fallback lightweight check
            jdbc.queryForObject("SELECT 1 FROM " + safeQuote(tableName) + " LIMIT 1", Integer.class);
            log.debug("tableExists: table {} exists (via fallback)", tableName);
            if (cachedExistingTables == null) cachedExistingTables = new java.util.HashSet<>();
            cachedExistingTables.add(up);
            return true;
        } catch (Exception ex2) {
            log.debug("tableExists: table {} does not exist (fallback)", tableName);
            return false;
        }
    }

    /**
     * Ensures all columns referenced in rowValues exist on the table; adds them if missing.
     * Delegates to {@link #ensureColumnsPresent(String, Map, Map)} with no type hints.
     *
     * @param tableName the target table name
     * @param rowValues the row map containing column names to check/create
     */
    private void ensureColumnsPresent(String tableName, Map<String, Object> rowValues) {
         ensureColumnsPresent(tableName, rowValues, null);
     }

    /**
     * Ensures all columns referenced in rowValues exist on the table; dynamically adds missing ones.
     */
     private void ensureColumnsPresent(String actualTableName, Map<String, Object> rowValues, Map<String, String> columnTypeHints) {
        try {
            Set<String> existing = getExistingColumns(actualTableName);
            for (String col : rowValues.keySet()) {
                if (col.equalsIgnoreCase("case_instance_id") || col.equalsIgnoreCase("id")) continue;
                if (existing.contains(col.toUpperCase())) continue;
                if (!isValidIdentifier(col)) {
                    log.warn("ensureColumnsPresent: skipping invalid identifier {}", col);
                    continue;
                }
                String hint = columnTypeHints == null ? null : columnTypeHints.get(col);
                String colType = determineColumnType(rowValues.get(col), hint);
                String alter = String.format("ALTER TABLE %s ADD COLUMN %s %s", safeQuote(actualTableName), safeQuote(col), colType);
                try {
                    executeDdlAutocommit(alter);
                    log.info("ensureColumnsPresent: added missing column {} to {}", col, actualTableName);
                    existing.add(col.toUpperCase());
                } catch (Exception ex) {
                    log.debug("ensureColumnsPresent: failed to add column {} to {}: {}", col, actualTableName, ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.debug("ensureColumnsPresent: error checking/adding columns for {}: {}", actualTableName, ex.getMessage());
        }
    }

    private Set<String> getExistingColumns(String actualTableName) {
        if (actualTableName == null) return java.util.Collections.emptySet();
        String upLogical = actualTableName.toUpperCase(java.util.Locale.ROOT);
        // Check cache
        Set<String> cached = cachedTableColumns.get(upLogical);
        if (cached != null) return cached;

        Set<String> cols = new HashSet<>();
        java.sql.Connection conn = null;
        try {
            conn = jdbc.getDataSource() == null ? null : jdbc.getDataSource().getConnection();
            if (conn != null) {
                java.sql.ResultSet rs = null;
                try {
                    rs = conn.getMetaData().getColumns(null, null, actualTableName, null);
                    while (rs.next()) {
                        String col = rs.getString("COLUMN_NAME");
                        if (col != null) cols.add(col.toUpperCase(java.util.Locale.ROOT));
                    }
                    if (cols.isEmpty()) {
                        // Fallback: try uppercase version if actualTableName didn't work (some drivers are picky)
                        rs.close();
                        rs = conn.getMetaData().getColumns(null, null, actualTableName.toUpperCase(java.util.Locale.ROOT), null);
                        while (rs.next()) {
                            String col = rs.getString("COLUMN_NAME");
                            if (col != null) cols.add(col.toUpperCase(java.util.Locale.ROOT));
                        }
                    }
                    if (!cols.isEmpty()) {
                        cachedTableColumns.put(upLogical, cols);
                        return cols;
                    }
                } finally {
                    if (rs != null) try { rs.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ex) {
            log.debug("getExistingColumns: metadata fallback failed for {}: {}", actualTableName, ex.getMessage());
        } finally {
            if (conn != null) try { conn.close(); } catch (Exception ignored) {}
        }
        return cols;
    }

    /**
     * Execute DDL statements (CREATE/ALTER/INDEX) on a dedicated autocommit connection.
     */
    private void executeDdlAutocommit(String sql) {
        if (sql == null || sql.isBlank()) return;
        java.sql.Connection conn = null;
        java.sql.Statement st = null;
        try {
            conn = jdbc.getDataSource() == null ? null : jdbc.getDataSource().getConnection();
            if (conn == null) return;
            boolean prevAuto = true;
            try {
                try { prevAuto = conn.getAutoCommit(); } catch (Exception ignored) {}
                conn.setAutoCommit(true);
                st = conn.createStatement();
                st.execute(sql);
            } finally {
                if (st != null) try { st.close(); } catch (Exception ignored) {}
                try { conn.setAutoCommit(prevAuto); } catch (Exception ignored) {}
            }
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (msg.contains("already exists") || msg.contains("column already exists") || msg.contains("duplicate table")) {
                return;
            }
            throw new RuntimeException(ex);
        } finally {
            if (conn != null) try { conn.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Creates a default work table with standard columns and indexes.
     */
    private void createDefaultWorkTable(String tableName, Map<String, Object> rowValues) {
        try {
            if (!isValidIdentifier(tableName)) {
                log.error("createDefaultWorkTable: invalid table name: {}", tableName);
                return;
            }

            synchronized (tableName.intern()) {
                if (tableExists(tableName)) return;

                String idColumnDef = isH2() 
                    ? "id VARCHAR(255) DEFAULT RANDOM_UUID() PRIMARY KEY" 
                    : "id VARCHAR(255) PRIMARY KEY";

                StringBuilder createTableSql = new StringBuilder();
                createTableSql.append("CREATE TABLE ").append(safeQuote(tableName)).append(" (");
                createTableSql.append(idColumnDef).append(", ");
                createTableSql.append("case_instance_id VARCHAR(255) NOT NULL UNIQUE, ");

                String payloadType = isH2() ? "CLOB" : "LONGTEXT";
                createTableSql.append("plain_payload ").append(payloadType).append(", ");
                createTableSql.append("requested_by VARCHAR(255)");
                createTableSql.append(", created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                createTableSql.append(", updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                createTableSql.append(")");

                try {
                    executeDdlAutocommit(createTableSql.toString());
                } catch (Exception e) {
                    log.debug("createDefaultWorkTable: CREATE TABLE failed (benign race?) - ignoring: {}", e.getMessage());
                }

                String actualTable = resolveActualTableName(tableName);
                ensureColumnsPresent(actualTable, rowValues, null);

                try {
                    String idx1 = String.format("CREATE INDEX IF NOT EXISTS idx_%s_case_instance_id ON %s(case_instance_id)", tableName, safeQuote(actualTable));
                    String idx2 = String.format("CREATE INDEX IF NOT EXISTS idx_%s_created_at ON %s(created_at)", tableName, safeQuote(actualTable));
                    executeDdlAutocommit(idx1);
                    executeDdlAutocommit(idx2);
                 } catch (Exception ignored) {}
            }
        } catch (Exception ex) {
            log.error("createDefaultWorkTable: failed to create table {}: {}", tableName, ex.getMessage(), ex);
        }
    }

    /**
     * Determines appropriate SQL column type based on the value type.
     * Delegates to {@link #determineColumnType(Object, String)} with no type hint.
     *
     * @param value the sample value to infer type from
     * @return a SQL type string (e.g., "VARCHAR(255)", "BIGINT", "DECIMAL(19,4)")
     */
    private String determineColumnType(Object value) {
         return determineColumnType(value, null);
     }

    /**
     * Determines appropriate SQL column type based on a type hint or value inference.
     *
     * <p>Type Hint Precedence (if hint is provided):
     * <ul>
     *   <li>"bigint", "long" → BIGINT</li>
     *   <li>"decimal", "number" → DECIMAL(19,4)</li>
     *   <li>"boolean" → BOOLEAN</li>
     *   <li>"timestamp", "datetime", "date" → TIMESTAMP</li>
     *   <li>"text" → LONGTEXT</li>
     *   <li>"string" or any unrecognized hint → VARCHAR(255); if hint starts with "varchar", "char", or "decimal", return as-is</li>
     * </ul>
     *
     * <p>Value-Based Inference (if no hint provided):
     * <ul>
     *   <li>null → LONGTEXT (safe default for unknown types)</li>
     *   <li>Integer, Long → BIGINT</li>
     *   <li>Double, Float → DECIMAL(19,4)</li>
     *   <li>Boolean → BOOLEAN</li>
     *   <li>java.time.temporal.Temporal, java.util.Date → TIMESTAMP</li>
     *   <li>String (length ≤ 255) → VARCHAR(255)</li>
     *   <li>String (length > 255) → LONGTEXT</li>
     *   <li>Complex types (Map, List, etc.) → LONGTEXT (typically serialized to JSON)</li>
     * </ul>
     *
     * @param value the sample value to infer type from; may be null
     * @param hint optional SQL type hint (e.g., "DECIMAL(10,2)", "VARCHAR(100)", "BIGINT"); if provided, takes precedence over value type
     * @return a SQL type string suitable for CREATE TABLE or ALTER TABLE ADD COLUMN
     */
     private boolean isH2() {
         if (cachedIsH2 != null) return cachedIsH2;
         try {
             String db = jdbc.getDataSource() == null ? null : jdbc.getDataSource().getConnection().getMetaData().getDatabaseProductName();
             if (db != null && db.toLowerCase(java.util.Locale.ROOT).contains("h2")) {
                 cachedIsH2 = true;
                 return true;
             }
         } catch (Exception ignored) {
             // Avoid repeatedly grabbing connections if the pool is under pressure; assume H2 by default in failure cases
             cachedIsH2 = true;
             return true;
         }
         cachedIsH2 = false;
         return false;
     }
   
     private String determineColumnType(Object value, String hint) {
        // If mapping provides a hint, honor common types
        if (hint != null) {
            String h = hint.trim().toLowerCase(java.util.Locale.ROOT);
            switch (h) {
                case "bigint": case "long": return "BIGINT";
                case "decimal": case "number": return "DECIMAL(19,4)";
                case "boolean": return "BOOLEAN";
                case "timestamp": case "datetime": case "date": return "TIMESTAMP";
                case "text": return "LONGTEXT";
                case "string": default:
                    // if explicit VARCHAR size provided (e.g., VARCHAR(100)) return as-is
                    if (h.startsWith("varchar") || h.startsWith("char") || h.startsWith("decimal")) return hint;
                    return "VARCHAR(255)";
            }
        }
 
        if (value == null) {
            return isH2() ? "CLOB" : "LONGTEXT";
        }
 
        if (value instanceof Integer || value instanceof Long) {
            return "BIGINT";
        }
 
        if (value instanceof Double || value instanceof Float) {
            return "DECIMAL(19,4)";
        }
 
        if (value instanceof Boolean) {
            return "BOOLEAN";
        }
 
        if (value instanceof java.time.temporal.Temporal ||
            value instanceof java.util.Date) {
            return "TIMESTAMP";
        }
 
        if (value instanceof String str) {
            if (str.length() > 255) {
                return isH2() ? "CLOB" : "LONGTEXT";
            }
            return "VARCHAR(255)";
        }
 
        // Default for complex types (JSON, arrays, objects)
        return isH2() ? "CLOB" : "LONGTEXT";
     }

    /**
     * Builds a dynamic UPSERT SQL statement based on the provided table name and column order.
     */
     private String buildUpsertSql(String actualTableName, java.util.List<String> columnOrder) {
         if (columnOrder == null || columnOrder.isEmpty()) throw new IllegalArgumentException("No columns to upsert");
         java.util.List<String> placeholders = new java.util.ArrayList<>();
         for (int i = 0; i < columnOrder.size(); i++) placeholders.add("?");
         String values = String.join(", ", placeholders);

         // Identify key column and build update assignments
         String keyCol = null;
         StringBuilder updateAssignments = new StringBuilder();
         for (String col : columnOrder) {
             if (col.equalsIgnoreCase("case_instance_id")) {
                 keyCol = col;
                 continue;
             }
             if (updateAssignments.length() > 0) updateAssignments.append(", ");
             updateAssignments.append(safeQuote(col)).append("=src.").append(safeQuote(col));
         }

         if (keyCol == null) {
             String colsQuoted = String.join(", ", columnOrder.stream().map(this::safeQuote).toArray(String[]::new));
             return String.format("INSERT INTO %s (%s) VALUES (%s)", safeQuote(actualTableName), colsQuoted, values);
         }

         try {
             String db = "";
             try { db = jdbc.getDataSource() == null ? "" : jdbc.getDataSource().getConnection().getMetaData().getDatabaseProductName(); } catch (Exception ignored) {}
             String dbLower = db == null ? "" : db.toLowerCase(java.util.Locale.ROOT);

             if (dbLower.contains("h2")) {
                 return "__H2_SELECT_UPDATE_INSERT__";
             } else if (dbLower.contains("postgres") || dbLower.contains("oracle") || dbLower.contains("sqlserver")) {
                 String[] srcColsArr = columnOrder.stream().map(this::safeQuote).toArray(String[]::new);
                 String srcCols = String.join(", ", srcColsArr);
                 String insertVals = java.util.stream.IntStream.range(0, columnOrder.size()).mapToObj(i -> "src." + safeQuote(columnOrder.get(i))).collect(java.util.stream.Collectors.joining(", "));
                 StringBuilder merge = new StringBuilder();
                 merge.append("MERGE INTO ").append(safeQuote(actualTableName)).append(" AS t USING (VALUES (").append(values).append(")) AS src(").append(srcCols).append(") ON t.").append(safeQuote(keyCol)).append(" = src.").append(safeQuote(keyCol)).append(" ");
                 if (updateAssignments.length() > 0) merge.append("WHEN MATCHED THEN UPDATE SET ").append(updateAssignments.toString()).append(" ");
                 merge.append("WHEN NOT MATCHED THEN INSERT (").append(srcCols).append(") VALUES (").append(insertVals).append(")");
                 return merge.toString();
             } else if (dbLower.contains("mysql") || dbLower.contains("mariadb")) {
                 String colsQuoted = String.join(", ", columnOrder.stream().map(this::safeQuote).toArray(String[]::new));
                 StringBuilder updateClauseMysql = new StringBuilder();
                 for (String col : columnOrder) {
                     if (col.equalsIgnoreCase("case_instance_id")) continue;
                     if (updateClauseMysql.length() > 0) updateClauseMysql.append(", ");
                     updateClauseMysql.append(safeQuote(col)).append("=VALUES(").append(safeQuote(col)).append(")");
                 }
                 return String.format("INSERT INTO %s (%s) VALUES (%s) ON DUPLICATE KEY UPDATE %s", safeQuote(actualTableName), colsQuoted, values, updateClauseMysql.toString());
             }
             String colsQuoted = String.join(", ", columnOrder.stream().map(this::safeQuote).toArray(String[]::new));
             return String.format("INSERT INTO %s (%s) VALUES (%s)", safeQuote(actualTableName), colsQuoted, values);
         } catch (Exception ignored) {
             String colsQuoted = String.join(", ", columnOrder.stream().map(this::safeQuote).toArray(String[]::new));
             return String.format("INSERT INTO %s (%s) VALUES (%s)", safeQuote(actualTableName), colsQuoted, values);
         }
     }

     /**
      * H2-specific robust fallback: perform a SELECT to check existence then UPDATE or INSERT atomically
      */
     private void h2SelectUpdateInsert(String actualTable, java.util.List<String> columnOrder, Map<String, Object> rowValues) {
         if (actualTable == null || actualTable.trim().isEmpty() || columnOrder == null || columnOrder.isEmpty()) return;
         try {
             Object keyVal = rowValues.get("case_instance_id");
             String placeholders = String.join(", ", java.util.Collections.nCopies(columnOrder.size(), "?"));
             Object[] insertParams = columnOrder.stream().map(rowValues::get).toArray();
             String safeCols = String.join(", ", columnOrder.stream().map(this::safeQuote).toArray(String[]::new));

             if (keyVal == null) {
                 jdbc.update(String.format("INSERT INTO %s (%s) VALUES (%s)", safeQuote(actualTable), safeCols, placeholders), insertParams);
                 return;
             }

             Integer count = null;
             try {
                 String existsSql = String.format("SELECT COUNT(1) FROM %s WHERE %s = ?", safeQuote(actualTable), safeQuote("case_instance_id"));
                 count = jdbc.queryForObject(existsSql, new Object[]{keyVal}, Integer.class);
             } catch (Exception ex) {
                 log.debug("h2SelectUpdateInsert: existence check failed for {}: {}", actualTable, ex.getMessage());
             }

             if (count != null && count > 0) {
                 StringBuilder set = new StringBuilder();
                 java.util.List<Object> params = new java.util.ArrayList<>();
                 for (String col : columnOrder) {
                     if (col.equalsIgnoreCase("case_instance_id")) continue;
                     if (set.length() > 0) set.append(", ");
                     set.append(safeQuote(col)).append(" = ?");
                     params.add(rowValues.get(col));
                 }
                 if (params.isEmpty()) return;
                 params.add(keyVal);
                 jdbc.update(String.format("UPDATE %s SET %s WHERE %s = ?", safeQuote(actualTable), set.toString(), safeQuote("case_instance_id")), params.toArray());
             } else {
                 jdbc.update(String.format("INSERT INTO %s (%s) VALUES (%s)", safeQuote(actualTable), safeCols, placeholders), insertParams);
             }
         } catch (Exception ex) {
             log.error("h2SelectUpdateInsert failed for table {}: {}", actualTable, ex.getMessage(), ex);
             throw new RuntimeException(ex);
         }
     }

    /**
     * Builds a dynamic UPSERT SQL statement from a row map.
     *
     * <p>This helper method delegates to {@link #buildUpsertSql(String, java.util.List)} after
     * building a deterministic column order via {@link #upsertColumnOrder(Map)}.
     *
     * @param tableName the target table name
     * @param rowValues the row map (column name to value); order is normalized internally
     * @return a parameterized SQL string with ? placeholders
     */
    private String buildUpsertSql(String tableName, Map<String, Object> rowValues) {
        // Build column list in deterministic order (include 'id' if present)
        java.util.List<String> columnNames = new java.util.ArrayList<>();
        // Ensure case_instance_id first when present
        if (rowValues.containsKey("case_instance_id")) columnNames.add("case_instance_id");
        for (String c : rowValues.keySet()) {
            if (columnNames.contains(c)) continue;
            columnNames.add(c);
        }
        return buildUpsertSql(tableName, columnNames);
    }

    /**
     * Validates that a table or column identifier is safe for use in SQL statements.
     *
     * <p>Pattern: Must match {@code ^[a-zA-Z_$][a-zA-Z0-9_$]*$} to prevent SQL injection.
     * Allows alphanumeric characters, underscores, and dollar signs per standard DB naming conventions.
     *
     * @param identifier the table or column name to validate
     * @return true if the identifier is safe for unquoted use in SQL; false otherwise
     */
    private boolean isValidIdentifier(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) return false;
        // Allow alphanumeric, underscore, and dollar sign (common DB naming conventions)
        return identifier.matches("^[a-zA-Z_$][a-zA-Z0-9_$]*$");
    }

    private boolean isEmptyResult(Object val) {
        if (val == null) return true;
        if (val instanceof java.util.Collection && ((java.util.Collection<?>) val).isEmpty()) return true;
        if (val instanceof java.util.Map && ((java.util.Map<?, ?>) val).isEmpty()) return true;
        String s = val.toString().trim();
        return s.equals("[]") || s.equals("{}");
    }

    private String safeQuote(String id) {
        if (id == null) return "";
        if (isValidIdentifier(id)) return id;
        return "\"" + id + "\"";
    }

    /**
     * Build deterministic column order for upsert operations (skips auto-managed 'id').
     */
    private java.util.List<String> upsertColumnOrder(Map<String, Object> row) {
        java.util.List<String> cols = new java.util.ArrayList<>();
        // ensure case_instance_id first if present
        if (row.containsKey("case_instance_id")) cols.add("case_instance_id");
        for (String k : row.keySet()) {
            if (k.equalsIgnoreCase("case_instance_id")) continue;
            // include id and any other columns in the natural map order
            cols.add(k);
        }
        return cols;
    }
}
