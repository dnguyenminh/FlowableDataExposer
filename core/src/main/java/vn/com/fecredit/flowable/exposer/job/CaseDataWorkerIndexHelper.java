package vn.com.fecredit.flowable.exposer.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import vn.com.fecredit.flowable.exposer.service.IndexLoader;

import java.util.List;
import java.util.Map;

/**
 * Processes index definitions and produces rows for auxiliary index tables.
 * Previously nested inside {@link CaseDataWorkerService}; extracted to its own
 * class to satisfy the 200‑line rule and make testing easier.
 */
public class CaseDataWorkerIndexHelper {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CaseDataWorkerIndexHelper.class);

    private final ObjectMapper om;
    private final CaseDataWorkerRowHelper db;
    private final CaseDataWorkerIndexRowBuilder rowBuilder;
    private final IndexLoader indexLoader;

    public CaseDataWorkerIndexHelper(ObjectMapper om, CaseDataWorkerRowHelper db, IndexLoader indexLoader) {
        this.om = om;
        this.db = db;
        this.rowBuilder = new CaseDataWorkerIndexRowBuilder(om);
        this.indexLoader = indexLoader;
    }

    public void processIndexDefinition(vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition def,
                                       String caseInstanceId,
                                       String annotatedJson,
                                       Object rowCreatedAt) {
        if (def == null || def.mappings == null || def.mappings.isEmpty()) return;
        try {
            String rootPath = def.jsonPath == null || def.jsonPath.isBlank() ? "$" : def.jsonPath;
            Object extracted = null;
            try {
                extracted = JsonPath.read(annotatedJson, rootPath);
            } catch (Exception ignored) {
                try { extracted = rowBuilder.tryAlternateRoots(annotatedJson, rootPath); } catch (Exception ignored2) {}
            }
            if (extracted == null) {
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
                try {
                    if (extracted == null && def.jsonPath != null && def.jsonPath.contains("rules")) {
                        Object deep = null;
                        try { deep = JsonPath.read(annotatedJson, "$..rules"); } catch (Exception ignored4) { deep = null; }
                        if (deep != null) extracted = deep;
                    }
                } catch (Exception ignored5) {}
            }
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
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
                List<?> items = (List<?>) extracted;
                for (Object item : items) {
                    String jsonForItem = rowBuilder.toJsonSafe(item);
                    Map<String, Object> row = rowBuilder.buildIndexRow(def, caseInstanceId, jsonForItem, rowCreatedAt);
                    rows.add(row);
                }
            } else if (extracted instanceof java.util.Map) {
                Map<?, ?> mapEntries = (Map<?, ?>) extracted;
                if (expandMapEntries) {
                    for (Map.Entry<?, ?> entry : mapEntries.entrySet()) {
                        Object k = entry.getKey();
                        if (k instanceof String && ((String) k).startsWith("@")) {
                            log.debug("processIndexDefinition: skipping metadata map key {} for table {}", k, def.table);
                            continue;
                        }
                        java.util.Map<String, Object> entryMap = new java.util.HashMap<>();
                        entryMap.put("_key", entry.getKey());
                        entryMap.put("_value", entry.getValue());
                        String jsonForItem = rowBuilder.toJsonSafe(entryMap);
                        Map<String, Object> row = rowBuilder.buildIndexRow(def, caseInstanceId, jsonForItem, rowCreatedAt);
                        rows.add(row);
                        log.debug("processIndexDefinition: expanded map entry key={} for table {}", entry.getKey(), def.table);
                    }
                } else {
                    String jsonForItem = rowBuilder.toJsonSafe(extracted);
                    Map<String, Object> row = rowBuilder.buildIndexRow(def, caseInstanceId, jsonForItem, rowCreatedAt);
                    rows.add(row);
                }
            } else {
                String jsonForItem = rowBuilder.toJsonSafe(extracted);
                Map<String, Object> row = rowBuilder.buildIndexRow(def, caseInstanceId, jsonForItem, rowCreatedAt);
                rows.add(row);
            }
            if (!rows.isEmpty()) db.upsertRowsByMetadata(def.table, rows, def);
        } catch (Exception ex) {
            log.error("processIndexDefinition failed for case {} table {}: {}", caseInstanceId, def.table, ex.getMessage(), ex);

        }
    }

    /** Convenience wrapper used by callers that only have the index helper.
     *  Delegates to the underlying rowBuilder. */
    public String toJsonSafe(Object obj) {
        return rowBuilder.toJsonSafe(obj);
    }
}
