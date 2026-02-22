package vn.com.fecredit.flowable.exposer.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.com.fecredit.flowable.exposer.service.MetadataResolver;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates all row‑level operations that used to live inside the old
 * DbHelper/RowHelper nested class.  The outer class is quite small and most
 * of the work is delegated to two inner helpers so each class stays under the
 * 200‑line limit.
 */
public class CaseDataWorkerRowHelper {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CaseDataWorkerRowHelper.class);

    private final JdbcTemplate jdbc;
    private final MetadataResolver resolver;
    private final ObjectMapper om;
    private final CaseDataWorkerSchemaHelper schema;
    private final CaseDataWorkerDialectHelper dialect;

    private final Builder builder;
    private final Persister persister;

    public CaseDataWorkerRowHelper(JdbcTemplate jdbc,
                                   MetadataResolver resolver,
                                   ObjectMapper om,
                                   CaseDataWorkerSchemaHelper schema,
                                   CaseDataWorkerDialectHelper dialect) {
        this.jdbc = jdbc;
        this.resolver = resolver;
        this.om = om;
        this.schema = schema;
        this.dialect = dialect;
        this.builder = new Builder(jdbc, resolver, om, schema, dialect);
        this.persister = new Persister(jdbc, resolver, om, schema, dialect);
    }

    public Map<String, Object> fetchLatestRow(String caseInstanceId) {
        return builder.fetchLatestRow(caseInstanceId);
    }

    public void upsertPlain(String entityType,
                            String caseInstanceId,
                            String annotatedJson,
                            Object rowCreatedAt,
                            Map<String, MetadataDefinition.FieldMapping> effectiveMappings,
                            Map<String, String> legacyMappings,
                            Map<String, Object> directFallbacks) {
        persister.upsertPlain(entityType, caseInstanceId, annotatedJson, rowCreatedAt,
                              effectiveMappings, legacyMappings, directFallbacks);
    }

    /**
     * Exposed for use by {@link CaseDataWorkerIndexHelper} when it needs to
     * convert arbitrary objects back to JSON.
     */
    public String toJsonSafe(Object obj) {
        return builder.toJsonSafe(obj);
    }

    // convenience methods used by legacy tests via reflection
    public void createDefaultWorkTable(String tableName, Map<String, Object> rowValues) {
        // simply forward to schema helper
        schema.createDefaultWorkTable(tableName, rowValues);
    }

    public void upsertRowsByMetadata(String tableName, java.util.List<Map<String, Object>> rows, vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition def) {
        persister.upsertRowsByMetadata(tableName, rows, def);
    }

    /* --------------------------------------------------------------------- */
    /* inner helpers                                                            */
    /* --------------------------------------------------------------------- */
    private static class Builder {
        private final JdbcTemplate jdbc;
        private final MetadataResolver resolver;
        private final ObjectMapper om;
        private final CaseDataWorkerSchemaHelper schema;
        private final CaseDataWorkerDialectHelper dialect;

        Builder(JdbcTemplate jdbc, MetadataResolver resolver, ObjectMapper om, CaseDataWorkerSchemaHelper schema, CaseDataWorkerDialectHelper dialect) {
            this.jdbc = jdbc;
            this.resolver = resolver;
            this.om = om;
            this.schema = schema;
            this.dialect = dialect;
        }

        Map<String, Object> fetchLatestRow(String caseInstanceId) {
            log.info("Querying latest sys_case_data_store row for caseInstanceId={}", caseInstanceId);
            try {
                String sql = "SELECT entity_type, payload, created_at FROM sys_case_data_store WHERE case_instance_id = ? ORDER BY created_at DESC LIMIT 1";
                return jdbc.queryForObject(sql, new Object[]{caseInstanceId}, (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("entityType", rs.getString("entity_type"));
                    m.put("payload", rs.getString("payload"));
                    m.put("createdAt", rs.getTimestamp("created_at"));
                    return m;
                });
            } catch (Exception ex) {
                log.debug("fetchLatestRow: {}", ex.getMessage());
                return null;
            }
        }

        Map<String, Object> buildRowValues(String caseInstanceId,
                                           String annotatedJson,
                                           Object rowCreatedAt,
                                           Map<String, MetadataDefinition.FieldMapping> effectiveMappings,
                                           Map<String, String> legacyMappings,
                                           Map<String, Object> directFallbacks) {
            Map<String, Object> rowValues = new java.util.LinkedHashMap<>();
            rowValues.put("case_instance_id", caseInstanceId);

            if (effectiveMappings != null && !effectiveMappings.isEmpty()) {
                for (var entry : effectiveMappings.entrySet()) {
                    MetadataDefinition.FieldMapping fm = entry.getValue();
                    if (fm.jsonPath == null) continue;
                    try {
                        Object extractedValue = JsonPath.read(annotatedJson, fm.jsonPath);
                        Object valueToPut = extractedValue;
                        if (schema.isEmptyResult(valueToPut)) {
                            valueToPut = null;
                        }
                        if (valueToPut != null
                                && !(valueToPut instanceof String)
                                && !(valueToPut instanceof Number)
                                && !(valueToPut instanceof Boolean)
                                && !(valueToPut instanceof java.time.temporal.Temporal)
                                && !(valueToPut instanceof java.util.Date)) {
                            try {
                                valueToPut = om.writeValueAsString(valueToPut);
                            } catch (Exception se) {
                                valueToPut = valueToPut.toString();
                            }
                        }
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

            if (legacyMappings != null && !legacyMappings.isEmpty()) {
                for (var entry : legacyMappings.entrySet()) {
                    String columnName = entry.getKey();
                    String jsonPath = entry.getValue();
                    if (rowValues.containsKey(columnName)) continue;
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

            if (directFallbacks != null && !directFallbacks.isEmpty()) {
                directFallbacks.forEach((key, value) -> {
                    if (value != null && !rowValues.containsKey(key)) {
                        rowValues.put(key, value);
                    }
                });
            }

            if (!rowValues.containsKey("plain_payload")) {
                rowValues.put("plain_payload", annotatedJson);
            }
            if (rowCreatedAt != null && !rowValues.containsKey("created_at")) {
                rowValues.put("created_at", rowCreatedAt);
            }

            log.debug("buildRowValues: extracted {} columns for case {}", rowValues.size(), caseInstanceId);
            return rowValues;
        }

        String toJsonSafe(Object obj) {
            try { return om.writeValueAsString(obj); } catch (Exception e) { return obj == null ? "null" : obj.toString(); }
        }
    }

    private static class Persister {
        private final JdbcTemplate jdbc;
        private final MetadataResolver resolver;
        private final ObjectMapper om;
        private final CaseDataWorkerSchemaHelper schema;
        private final CaseDataWorkerDialectHelper dialect;

        Persister(JdbcTemplate jdbc, MetadataResolver resolver, ObjectMapper om, CaseDataWorkerSchemaHelper schema, CaseDataWorkerDialectHelper dialect) {
            this.jdbc = jdbc;
            this.resolver = resolver;
            this.om = om;
            this.schema = schema;
            this.dialect = dialect;
        }

        void upsertPlain(String entityType, String caseInstanceId, String annotatedJson, Object rowCreatedAt,
                         Map<String, MetadataDefinition.FieldMapping> effectiveMappings,
                         Map<String, String> legacyMappings, Map<String, Object> directFallbacks) {
            try {
                MetadataDefinition metaDef = resolver.resolveForClass(entityType);
                log.info("upsertPlain: resolver.resolveForClass({}) => {}", entityType, metaDef == null ? null : metaDef._class);
                if (metaDef != null) log.debug("upsertPlain: resolved metadata json: {}", om.writeValueAsString(metaDef));

                Map<String, Object> rowValues = new Builder(jdbc, resolver, om, schema, dialect)
                        .buildRowValues(caseInstanceId, annotatedJson, rowCreatedAt, effectiveMappings, legacyMappings, directFallbacks);
                rowValues.put("plain_payload", annotatedJson);

                upsertRowByMetadata(metaDef.tableName, rowValues);
            } catch (Exception ex) {
                log.error("upsertPlain: Failed to upsert plain data for case {}", caseInstanceId, ex);
            }
        }

        private void upsertRowByMetadata(String tableName, Map<String, Object> rowValues) {
            if (tableName == null || tableName.trim().isEmpty() || rowValues.isEmpty()) {
                log.warn("upsertRowByMetadata: invalid arguments - tableName={}, rowCount={}", tableName, rowValues.size());
                return;
            }

            try {
                if (!schema.isValidIdentifier(tableName)) {
                    log.error("upsertRowByMetadata: invalid table name: {}", tableName);
                    return;
                }
                if (!schema.tableExists(tableName)) {
                    log.info("upsertRowByMetadata: table {} does not exist, creating with default schema", tableName);
                    schema.createDefaultWorkTable(tableName, rowValues);
                    log.info("upsertRowByMetadata: successfully created table {}", tableName);
                }
                String actualTable = resolveActualTableName(tableName);
                schema.ensureColumnsPresent(actualTable, rowValues, null);
                List<String> colOrder = schema.upsertColumnOrder(rowValues);
                String upsertSql = dialect.buildUpsertSql(actualTable, colOrder);
                log.debug("upsertRowByMetadata: executing SQL for table {} with {} columns", actualTable, colOrder.size());
                Object[] paramValues = colOrder.stream().map(rowValues::get).toArray();
                try {
                    if ("__H2_SELECT_UPDATE_INSERT__".equals(upsertSql) && dialect.isH2()) {
                        System.out.println("SQL-INSTRUMENT: Routing to H2 fallback for table=" + actualTable + " params=" + java.util.Arrays.toString(paramValues));
                        dialect.h2SelectUpdateInsert(actualTable, colOrder, rowValues);
                    } else {
                        System.out.println("SQL-INSTRUMENT: Executing upsert SQL: " + upsertSql + " params=" + java.util.Arrays.toString(paramValues));
                        jdbc.update(upsertSql, paramValues);
                    }
                } catch (org.springframework.jdbc.BadSqlGrammarException badSql) {
                    if (dialect.isH2()) {
                        try {
                            log.info("upsertRowByMetadata: falling back to SELECT→UPDATE/INSERT for H2 on table {}", actualTable);
                            dialect.h2SelectUpdateInsert(actualTable, colOrder, rowValues);
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

        private void upsertRowsByMetadata(String tableName, java.util.List<Map<String, Object>> rows, vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition def) {
            if (rows == null || rows.isEmpty()) return;
            try {
                Map<String, String> hints = new java.util.HashMap<>();
                if (def != null && def.mappings != null) {
                    for (vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition.IndexField f : def.mappings) {
                        String col = f.plainColumn != null && !f.plainColumn.isBlank() ? f.plainColumn : f.jsonPath.replaceAll("[^a-zA-Z0-9_]", "_");
                        if (f.type != null && !f.type.isBlank()) hints.put(col, f.type);
                    }
                }
                if (!schema.tableExists(tableName)) {
                    log.info("upsertRowsByMetadata: table {} does not exist, creating with default schema", tableName);
                    schema.createDefaultWorkTable(tableName, rows.get(0));
                }
                String actualTable = resolveActualTableName(tableName);
                for (Map<String, Object> row : rows) {
                    schema.ensureColumnsPresent(actualTable, row, hints);
                }
                for (Map<String, Object> row : rows) {
                    java.util.List<String> columnOrder = schema.upsertColumnOrder(row);
                    java.util.List<Object> paramsList = new java.util.ArrayList<>();
                    for (String col : columnOrder) {
                        Object val = row.get(col);
                        if (schema.isEmptyResult(val)) val = null;
                        paramsList.add(val);
                    }
                    if (paramsList.isEmpty()) continue;
                    String upsertSql = dialect.buildUpsertSql(actualTable, columnOrder);
                    Object[] params = paramsList.toArray();
                    try {
                        if ("__H2_SELECT_UPDATE_INSERT__".equals(upsertSql) && dialect.isH2()) {
                            dialect.h2SelectUpdateInsert(actualTable, columnOrder, row);
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
            return schema.resolveActualTableName(logicalName);
        }
    }
}