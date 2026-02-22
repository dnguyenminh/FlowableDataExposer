package vn.com.fecredit.flowable.exposer.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.com.fecredit.flowable.exposer.service.MetadataResolver;
import vn.com.fecredit.flowable.exposer.service.MetadataAnnotator;
import vn.com.fecredit.flowable.exposer.service.IndexLoader;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helper component extracted from {@link CaseDataWorker} to keep the worker class small.
 *
 * <p>This class contains the bulk of the reindex logic, dynamic table/column DDL and
 * upsert utilities.  It is deliberately package-private since only the worker needs
 * to instantiate it.
 */
class CaseDataWorkerService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CaseDataWorkerService.class);

    private final MetadataResolver resolver;
    private final ObjectMapper om;
    private final MetadataAnnotator annotator;
    private final IndexLoader indexLoader;

    // helpers
    private final CaseDataWorkerSchemaHelper schema;
    private final CaseDataWorkerRowHelper db;
    private final CaseDataWorkerIndexHelper idx;

    // caches from original class (migrated into DbHelper as needed)

    CaseDataWorkerService(JdbcTemplate jdbc,
                          MetadataResolver resolver,
                          ObjectMapper om,
                          MetadataAnnotator annotator,
                          vn.com.fecredit.flowable.exposer.service.IndexLoader indexLoader) {
        this.resolver = resolver;
        this.om = om;
        this.annotator = annotator;
        this.indexLoader = indexLoader;
        CaseDataWorkerDialectHelper dialect = new CaseDataWorkerDialectHelper(jdbc);
        this.schema = new CaseDataWorkerSchemaHelper(jdbc, om, dialect);
        this.db = new CaseDataWorkerRowHelper(jdbc, resolver, om, schema, dialect);
        this.idx = new CaseDataWorkerIndexHelper(om, db, indexLoader);
    }

    /** Public entry point used by worker. */
    public void reindexByCaseInstanceId(String caseInstanceId) {
        log.info("reindexByCaseInstanceId - start caseInstanceId={}", caseInstanceId);
        try {
            Map<String, Object> row = db.fetchLatestRow(caseInstanceId);
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

            try {
                System.out.println("DEBUG[CaseDataWorker]: processing caseInstanceId=" + caseInstanceId + " effectiveMappingsKeys=" + (effectiveMappings==null?"[]":effectiveMappings.keySet()));
            } catch (Exception ignored) {}

            db.upsertPlain(entityType, caseInstanceId, annotatedJson, rowCreatedAt, effectiveMappings, legacyMappings, directFallbacks);

            // indexes
            try {
                indexLoader.findByClass(entityType).ifPresent(def -> {
                    try { idx.processIndexDefinition(def, caseInstanceId, annotatedJson, rowCreatedAt); } catch (Exception e) { log.error("processIndexDefinition failed: {}", e.getMessage(), e); }
                });
                for (vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition other : indexLoader.all()) {
                    if (other == null) continue;
                    String keyClass = other._class != null ? other._class : other.workClassReference;
                    if (keyClass == null) continue;
                    if (keyClass.equals(entityType) || (other.workClassReference != null && other.workClassReference.equals(entityType))) continue;
                    if (other.jsonPath != null && !other.jsonPath.isBlank() && !"$".equals(other.jsonPath.trim())) {
                        try { idx.processIndexDefinition(other, caseInstanceId, annotatedJson, rowCreatedAt); } catch (Exception ignored) {}
                        continue;
                    }
                    try {
                        String expr = "$..[?(@['@class']=='" + keyClass + "')]";
                        Object matches = null;
                        try { matches = JsonPath.read(annotatedJson, expr); } catch (Exception jp) { matches = null; }
                        if (matches instanceof java.util.List) {
                            for (Object m : (java.util.List<?>) matches) {
                                try { idx.processIndexDefinition(other, caseInstanceId, idx.toJsonSafe(m), rowCreatedAt); } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ex) {
                log.error("Error processing index mappings for {}: {}", caseInstanceId, ex.getMessage(), ex);
            }

            log.info("reindexByCaseInstanceId - completed for {}", caseInstanceId);

        } catch (Exception ex) {
            log.error("reindex error for {}", caseInstanceId, ex);
        }
    }


    // additional helper methods will be added below

    private Map<String, MetadataDefinition.FieldMapping> computeEffectiveMappings(String entityType, Map<String, MetadataDefinition.FieldMapping> mappings) {
        if (mappings != null && !mappings.isEmpty()) return mappings;
        if (entityType == null) return java.util.Collections.emptyMap();
        String lower = entityType.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("process")) {
            String base = entityType.substring(0, entityType.length() - "process".length());
            if (!base.isBlank()) {
                String cand = Character.toUpperCase(base.charAt(0)) + base.substring(1);
                try { return resolver.mappingsMetadataFor(cand); } catch (Exception ignored) {}
            }
        }
        return java.util.Collections.emptyMap();
    }

}
