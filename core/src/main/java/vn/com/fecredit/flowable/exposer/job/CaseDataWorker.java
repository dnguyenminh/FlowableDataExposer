package vn.com.fecredit.flowable.exposer.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.com.fecredit.flowable.exposer.entity.SysExposeRequest;
import vn.com.fecredit.flowable.exposer.repository.SysExposeRequestRepository;
import vn.com.fecredit.flowable.exposer.service.MetadataAnnotator;
import vn.com.fecredit.flowable.exposer.service.MetadataResolver;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Background worker responsible for consuming {@code SysExposeRequest}s
 * and rebuilding index/plain tables from the append-only case data store.
 */
@Component
public class CaseDataWorker {

    private static final Logger log = LoggerFactory.getLogger(CaseDataWorker.class);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private MetadataAnnotator annotator;
    @Autowired
    private MetadataResolver resolver;
    @Autowired
    private SysExposeRequestRepository reqRepo;

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

            upsertPlain(caseInstanceId, annotatedJson, rowCreatedAt, effectiveMappings, legacyMappings, directFallbacks);

            log.info("reindexByCaseInstanceId - completed for {}", caseInstanceId);

        } catch (Exception ex) {
            log.error("reindex error for {}", caseInstanceId, ex);
        }
    }

    private Map<String, Object> fetchLatestRow(String caseInstanceId) {
        log.debug("Querying latest sys_case_data_store row for caseInstanceId={}", caseInstanceId);
        try {
            return jdbc.queryForObject(
                    "SELECT entity_type, payload, created_at FROM sys_case_data_store WHERE case_instance_id = ? ORDER BY created_at DESC LIMIT 1",
                    new Object[]{caseInstanceId}, (rs, rowNum) -> {
                        java.util.Map<String, Object> row = new java.util.HashMap<>();
                        row.put("entityType", rs.getString("entity_type"));
                        row.put("payload", rs.getString("payload"));
                        java.sql.Timestamp ts = rs.getTimestamp("created_at");
                        if (ts != null) {
                            row.put("createdAt", ts.toInstant());
                        }
                        return row;
                    });
        } catch (Exception ex) {
            log.debug("fetchLatestRow failed for {}: {}", caseInstanceId, ex.getMessage());
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

    private void upsertPlain(String caseInstanceId, String annotatedJson, Object rowCreatedAt,
                             Map<String, MetadataDefinition.FieldMapping> effectiveMappings,
                             Map<String, String> legacyMappings, Map<String, Object> directFallbacks) {
        try {
            // Validate metadata schema and extract required fields
            MetadataDefinition metaDef = resolver.resolveForClass(caseInstanceId);
            if (!validateWorkClassMetadataSchema(metaDef)) {
                log.warn("upsertPlain: metadata does not conform to Work Class Metadata Schema for case {}", caseInstanceId);
                return;
            }

            // Verify tableName is not empty
            if (metaDef.tableName == null || metaDef.tableName.trim().isEmpty()) {
                log.warn("upsertPlain: tableName is empty for case {}", caseInstanceId);
                return;
            }

            // Build row values from effective mappings and annotated JSON
            Map<String, Object> rowValues = buildRowValues(caseInstanceId, annotatedJson, rowCreatedAt, effectiveMappings, legacyMappings, directFallbacks);

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
                    Object extractedValue = JsonPath.read(annotatedJson, fm.jsonPath);
                    // Use plainColumn if specified, otherwise fall back to column name
                    String columnName = fm.plainColumn != null && !fm.plainColumn.trim().isEmpty()
                            ? fm.plainColumn
                            : fm.column;
                    if (columnName != null && !columnName.trim().isEmpty()) {
                        rowValues.put(columnName, extractedValue);
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

        // Set created_at timestamp if provided and not already set
        if (rowCreatedAt != null && !rowValues.containsKey("created_at")) {
            rowValues.put("created_at", rowCreatedAt);
        }

        log.debug("buildRowValues: extracted {} columns for case {}", rowValues.size(), caseInstanceId);
        return rowValues;
    }

    /**
     * Dynamically inserts or updates a row in the specified table.
     * If the table does not exist, creates it with a default schema based on rowValues.
     * Uses MERGE or INSERT ... ON DUPLICATE KEY UPDATE for upsert semantics.
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

            // Build dynamic upsert SQL
            String upsertSql = buildUpsertSql(tableName, rowValues);
            log.debug("upsertRowByMetadata: executing SQL for table {} with {} columns", tableName, rowValues.size());

            // Execute the upsert with parameterized values
            Object[] paramValues = rowValues.values().toArray();
            jdbc.update(upsertSql, paramValues);

            log.info("upsertRowByMetadata: successfully upserted {} rows into {}", 1, tableName);
        } catch (Exception ex) {
            log.error("upsertRowByMetadata: failed to upsert into table {}: {}", tableName, ex.getMessage(), ex);
        }
    }

    /**
     * Checks if a table exists in the database.
     * Handles database-specific metadata queries gracefully.
     */
    private boolean tableExists(String tableName) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_NAME = ? AND TABLE_SCHEMA = DATABASE()",
                    Integer.class,
                    tableName
            );
            boolean exists = count != null && count > 0;
            log.debug("tableExists: table {} exists = {}", tableName, exists);
            return exists;
        } catch (Exception ex) {
            // If information_schema is not available, try alternate approach
            log.debug("tableExists: checking table {} with fallback method: {}", tableName, ex.getMessage());
            try {
                jdbc.queryForObject("SELECT 1 FROM " + tableName + " LIMIT 1", Integer.class);
                log.debug("tableExists: table {} exists (via fallback)", tableName);
                return true;
            } catch (Exception ex2) {
                log.debug("tableExists: table {} does not exist", tableName);
                return false;
            }
        }
    }

    /**
     * Creates a default work table with standard columns and indexes.
     * Schema:
     * - id (BIGINT AUTO_INCREMENT PRIMARY KEY)
     * - case_instance_id (VARCHAR(255) UNIQUE NOT NULL)
     * - Dynamic columns from rowValues (LONGTEXT or appropriate type)
     * - created_at (TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
     * - updated_at (TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)
     */
    private void createDefaultWorkTable(String tableName, Map<String, Object> rowValues) {
        try {
            // Validate table name
            if (!isValidIdentifier(tableName)) {
                log.error("createDefaultWorkTable: invalid table name: {}", tableName);
                return;
            }

            // Build CREATE TABLE statement
            StringBuilder createTableSql = new StringBuilder();
            createTableSql.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (");
            createTableSql.append("id BIGINT AUTO_INCREMENT PRIMARY KEY, ");
            createTableSql.append("case_instance_id VARCHAR(255) NOT NULL UNIQUE, ");

            // Add standard work columns
            createTableSql.append("plain_payload LONGTEXT, ");
            createTableSql.append("requested_by VARCHAR(255), ");

            // Add dynamic columns based on rowValues
            boolean isFirstColumn = true;
            for (Map.Entry<String, Object> entry : rowValues.entrySet()) {
                String columnName = entry.getKey();
                Object value = entry.getValue();

                // Skip case_instance_id and created_at as they're already defined
                if (columnName.equals("case_instance_id") || columnName.equals("created_at") || columnName.equals("updated_at")) {
                    continue;
                }

                // Skip if column name is invalid
                if (!isValidIdentifier(columnName)) {
                    log.warn("createDefaultWorkTable: skipping invalid column name: {}", columnName);
                    continue;
                }

                if (!isFirstColumn) {
                    createTableSql.append(", ");
                }
                isFirstColumn = false;

                // Determine column type based on value
                String columnType = determineColumnType(value);
                createTableSql.append(columnName).append(" ").append(columnType);
            }

            // Add timestamp columns
            createTableSql.append(", created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
            createTableSql.append(", updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");

            // Add indexes
            createTableSql.append(", INDEX idx_case_instance_id (case_instance_id)");
            createTableSql.append(", INDEX idx_created_at (created_at)");
            createTableSql.append(")");

            log.debug("createDefaultWorkTable: executing CREATE TABLE SQL: {}", createTableSql);
            jdbc.execute(createTableSql.toString());
            log.info("createDefaultWorkTable: successfully created table {} with default schema", tableName);
        } catch (Exception ex) {
            log.error("createDefaultWorkTable: failed to create table {}: {}", tableName, ex.getMessage(), ex);
            throw new RuntimeException("Failed to create work table: " + tableName, ex);
        }
    }

    /**
     * Determines appropriate SQL column type based on the value type.
     */
    private String determineColumnType(Object value) {
        if (value == null) {
            return "LONGTEXT";
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
                return "LONGTEXT";
            }
            return "VARCHAR(255)";
        }

        // Default for complex types (JSON, arrays, objects)
        return "LONGTEXT";
    }

    /**
     * Builds a dynamic UPSERT SQL statement based on the provided table name and column values.
     * Falls back to INSERT if MERGE is not supported by the database.
     */
    private String buildUpsertSql(String tableName, Map<String, Object> rowValues) {
        java.util.List<String> columnNames = new java.util.ArrayList<>(rowValues.keySet());
        java.util.List<String> placeholders = new java.util.ArrayList<>();
        for (int i = 0; i < columnNames.size(); i++) {
            placeholders.add("?");
        }

        String columns = String.join(", ", columnNames);
        String values = String.join(", ", placeholders);

        // For now, use simple INSERT; database-specific MERGE/ON CONFLICT logic can be added later
        return String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columns, values);
    }

    /**
     * Validates that a table or column identifier is safe for use in SQL statements.
     */
    private boolean isValidIdentifier(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) return false;
        // Allow alphanumeric, underscore, and dollar sign (common DB naming conventions)
        return identifier.matches("^[a-zA-Z_$][a-zA-Z0-9_$]*$");
    }
}
