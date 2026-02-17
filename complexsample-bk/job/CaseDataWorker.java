package vn.com.fecredit.complexsample.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.com.fecredit.complexsample.entity.CasePlainOrder;
import vn.com.fecredit.complexsample.entity.SysExposeRequest;
import vn.com.fecredit.complexsample.repository.CasePlainOrderRepository;
import vn.com.fecredit.complexsample.repository.SysExposeRequestRepository;
import vn.com.fecredit.complexsample.service.MetadataAnnotator;
import vn.com.fecredit.complexsample.service.MetadataResolver;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import vn.com.fecredit.complexsample.service.metadata.MetadataDefinition;

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
    private CasePlainOrderRepository plainRepo;
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
            log.info("CaseDataWorker.pollAndProcess - found {} pending requests", pending == null ? 0 : pending.size());
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
                    new Object[]{caseInstanceId}, (rs, rowNum) -> Map.of(
                            "entityType", rs.getString("entity_type"),
                            "payload", rs.getString("payload"),
                            "createdAt", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()
                    ));
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
                             Map<String, String> legacyMappings, java.util.Map<String, Object> directFallbacks) {
        plainRepo.upsertByCaseInstanceId(caseInstanceId, (CasePlainOrder p) -> {
            p.setPlainPayload(annotatedJson);
            CaseDataWorkerHelpers.setCreatedAtIfMissing(p, rowCreatedAt);
            CaseDataWorkerHelpers.setRequestedByFromJson(p, annotatedJson);
            mapOrderTotal(p, annotatedJson, effectiveMappings);
            mapCustomerId(p, annotatedJson, effectiveMappings);
            mapOrderPriority(p, annotatedJson, effectiveMappings);
            applyLegacyFallbacks(p, annotatedJson, legacyMappings, directFallbacks);
            CaseDataWorkerHelpers.ensureDefaultPriority(p);
            try {
                log.info("Prepared plain for case {}: orderTotal={} customerId={} priority={}", caseInstanceId, p.getOrderTotal(), p.getCustomerId(), p.getOrderPriority());
            } catch (Exception ex) { log.debug("Failed to log prepared plain for case {}", caseInstanceId, ex); }
        }, plainRepo);
    }

    private void mapOrderTotal(CasePlainOrder p, String annotatedJson, Map<String, MetadataDefinition.FieldMapping> effectiveMappings) {
        try {
            if (effectiveMappings.values().stream().noneMatch(m -> "order_total".equals(m.plainColumn) || "order_total".equals(m.column))) return;
            Object v = null;
            for (var fm : effectiveMappings.values()) {
                if ("order_total".equals(fm.plainColumn) || "order_total".equals(fm.column)) {
                    try { v = JsonPath.read(annotatedJson, fm.jsonPath); } catch (Exception ignored) {}
                    break;
                }
            }
            if (v instanceof Number) p.setOrderTotal(((Number)v).doubleValue());
        } catch (Exception ex) { log.debug("Failed to map order_total: {}", ex.getMessage()); }
    }

    private void mapCustomerId(CasePlainOrder p, String annotatedJson, Map<String, MetadataDefinition.FieldMapping> effectiveMappings) {
        try {
            if (effectiveMappings.values().stream().anyMatch(m -> "customer_id".equals(m.plainColumn) || "customer_id".equals(m.column))) {
                Object v = null;
                for (var fm : effectiveMappings.values()) {
                    if ("customer_id".equals(fm.plainColumn) || "customer_id".equals(fm.column)) {
                        try { v = JsonPath.read(annotatedJson, fm.jsonPath); } catch (Exception ignored) {}
                        break;
                    }
                }
                if (v != null) { p.setCustomerId(String.valueOf(v)); return; }
            }
            // fallback to $.customer.id
            try { Object v = JsonPath.read(annotatedJson, "$.customer.id"); if (v != null) p.setCustomerId(String.valueOf(v)); } catch (Exception ignored) {}
        } catch (Exception ex) { log.debug("CustomerId mapping failed: {}", ex.getMessage()); }
    }

    private void mapOrderPriority(CasePlainOrder p, String annotatedJson, Map<String, MetadataDefinition.FieldMapping> effectiveMappings) {
        try {
            if (effectiveMappings.values().stream().noneMatch(m -> "order_priority".equals(m.plainColumn) || "order_priority".equals(m.column))) return;
            Object v = null;
            for (var fm : effectiveMappings.values()) {
                if ("order_priority".equals(fm.plainColumn) || "order_priority".equals(fm.column)) {
                    try { v = JsonPath.read(annotatedJson, fm.jsonPath); } catch (Exception ignored) {}
                    break;
                }
            }
            if (v != null) p.setOrderPriority(String.valueOf(v));
        } catch (Exception ex) { log.debug("Failed to map order_priority: {}", ex.getMessage()); }
    }

    private void applyLegacyFallbacks(CasePlainOrder p, String annotatedJson, Map<String, String> legacyMappings, java.util.Map<String, Object> directFallbacks) {
        try {
            if (p.getOrderTotal() == null) {
                String jp = legacyMappings.get("total_amount");
                if (jp != null) {
                    try { Object v = JsonPath.read(annotatedJson, jp); if (v instanceof Number) p.setOrderTotal(((Number)v).doubleValue()); } catch (Exception ignored) {}
                }
                if (p.getOrderTotal() == null) {
                    Object dt = directFallbacks.get("total");
                    if (dt instanceof Number) p.setOrderTotal(((Number)dt).doubleValue());
                }
            }
            if (p.getOrderPriority() == null) {
                Object dp = directFallbacks.get("priority");
                if (dp != null) p.setOrderPriority(String.valueOf(dp));
            }
        } catch (Exception ex) { log.debug("Legacy fallback failed: {}", ex.getMessage()); }
    }
}
