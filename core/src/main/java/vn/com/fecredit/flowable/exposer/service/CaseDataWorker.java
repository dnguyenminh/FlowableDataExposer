package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.com.fecredit.flowable.exposer.entity.CasePlainOrder;
import vn.com.fecredit.flowable.exposer.entity.SysExposeRequest;
import vn.com.fecredit.flowable.exposer.repository.CasePlainOrderRepository;
import vn.com.fecredit.flowable.exposer.repository.SysExposeRequestRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Background worker responsible for consuming {@code SysExposeRequest}s
 * and rebuilding index/plain tables from the append-only case data store.
 *
 * <p>Design notes:
 * - idempotent: reindex operations should be safe to run repeatedly
 * - defensive: failures are logged and do not crash the scheduler
 * - performance-sensitive: metadata lookups are cached (Caffeine)</p>
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

    /**
     * Scheduled poll that processes pending expose requests.
     *
     * Runs on a fixed delay and marks requests as DONE/FAILED; kept concise
     * — heavy lifting is delegated to {@link #reindexByCaseInstanceId(String)}.
     */
    @Scheduled(fixedDelay = 1000)
    public void pollAndProcess() {
        try {
            List<SysExposeRequest> pending = reqRepo.findByStatus("PENDING");
            for (SysExposeRequest r : pending) {
                try {
                    reindexByCaseInstanceId(r.getCaseInstanceId());
                    r.setStatus("DONE");
                    r.setProcessedAt(OffsetDateTime.now());
                    reqRepo.save(r);
                } catch (Exception ex) {
                    r.setStatus("FAILED");
                    reqRepo.save(r);
                    log.error("Failed to process expose request {}", r.getId(), ex);
                }
            }
        } catch (Exception ex) {
            // swallow to avoid crash
            log.error("CaseDataWorker.poll error", ex);
        }
    }

    @Transactional
    /**
     * Rebuild index/plain rows for a single case instance from the latest
     * sys_case_data_store blob. This method is transactional and intended to
     * be idempotent — callers (scheduled or admin) can safely retry on
     * transient failures.
     */
    public void reindexByCaseInstanceId(String caseInstanceId) {
        // find latest blob for this case
        try {
            var row = jdbc.queryForObject(
                    "SELECT entity_type, payload FROM sys_case_data_store WHERE case_instance_id = ? ORDER BY created_at DESC LIMIT 1",
                    new Object[]{caseInstanceId}, (rs, rowNum) -> Map.of("entityType", rs.getString("entity_type"), "payload", rs.getString("payload")));
            if (row == null) return;

            String entityType = (String) row.get("entityType");
            String payload = (String) row.get("payload");
            if (entityType == null) entityType = "Order"; // fallback

            // parse payload into Map
            Map<String, Object> vars;
            try {
                vars = om.readValue(payload, Map.class);
            } catch (Exception e) {
                // if payload wasn't JSON, bail
                log.warn("Payload for case {} is not valid JSON, skipping", caseInstanceId);
                return;
            }

            // annotate in-memory according to metadata (adds @class where needed)
            try {
                annotator.annotate(vars, entityType);
            } catch (Exception ex) {
                log.debug("Annotator failed for case {}", caseInstanceId, ex);
            }

            // reserialize annotated payload for JsonPath queries
            String annotatedJson = om.writeValueAsString(vars);

            // diagnostic logging to help debugging why mappings may not extract values
            log.debug("Annotated JSON for case {}: {}", caseInstanceId, annotatedJson);

            // extract mappings for this entity
            var mappings = resolver.mappingsMetadataFor(entityType);

            // resolved mappings and annotatedJson are now logged at debug level above

            // legacy (column->jsonPath) mapping - compute once and keep as final for lambda capture
            final java.util.Map<String, String> legacyMappings = resolver.mappingsFor(entityType);

            // precompute direct JsonPath fallbacks (used when metadata isn't available on classpath)
            final Double directTotalFallback;
            final String directPriorityFallback;
            Double _dt = null; String _pr = null;
            try {
                Object o = JsonPath.read(annotatedJson, "$.total");
                if (o instanceof Number) _dt = ((Number)o).doubleValue();
            } catch (Exception ignored) {}
            try {
                Object pval = JsonPath.read(annotatedJson, "$.meta.priority");
                if (pval != null) _pr = String.valueOf(pval);
            } catch (Exception ignored) {}
            directTotalFallback = _dt;
            directPriorityFallback = _pr;

            log.debug("Mappings for entityType {}: {}", entityType, mappings);
            log.debug("Legacy mappings for entityType {}: {}", entityType, legacyMappings);
            try {
                Object direct = JsonPath.read(annotatedJson, "$.total");
                log.debug("Direct JsonPath $.total resolved to: {}", direct);
            } catch (Exception ex) {
                log.debug("Direct JsonPath $.total failed: {}", ex.getMessage());
            }

            // prepare plain entity upsert
            plainRepo.upsertByCaseInstanceId(caseInstanceId, (CasePlainOrder p) -> {
                p.setPlainPayload(annotatedJson);
                // Map some well-known columns used by Order plain table
                try {
                    // order_total -> orderTotal
                    if (mappings.values().stream().anyMatch(m -> "order_total".equals(m.plainColumn) || "order_total".equals(m.column))) {
                        // try to read $.total or mapping jsonPath
                        Object v = null;
                        for (var fm : mappings.values()) {
                            log.debug("Checking mapping jsonPath='{}' plainColumn='{}' column='{}'", fm.jsonPath, fm.plainColumn, fm.column);
                            if ("order_total".equals(fm.plainColumn) || "order_total".equals(fm.column)) {
                                try { v = JsonPath.read(annotatedJson, fm.jsonPath); } catch (Exception ex) { log.debug("JsonPath read failed", ex); }
                                break;
                            }
                        }
                        if (v instanceof Number) p.setOrderTotal(((Number)v).doubleValue());
                    }
                } catch (Exception ex) { log.debug("Failed to map order_total for case {}", caseInstanceId, ex); }
                try {
                    if (mappings.values().stream().anyMatch(m -> "customer_id".equals(m.plainColumn) || "customer_id".equals(m.column))) {
                        Object v = null;
                        for (var fm : mappings.values()) {
                            if ("customer_id".equals(fm.plainColumn) || "customer_id".equals(fm.column)) {
                                try { v = JsonPath.read(annotatedJson, fm.jsonPath); } catch (Exception ex) { log.debug("JsonPath read failed", ex); }
                                // diagnostic visible at debug logs if needed
                                break;
                            }
                        }
                        if (v != null) p.setCustomerId(String.valueOf(v));
                    }
                } catch (Exception ex) { log.debug("Failed to map customer_id for case {}", caseInstanceId, ex); }
                // final fallback: try common nested path $.customer.id
                try {
                    if (p.getCustomerId() == null) {
                        try {
                            Object v = JsonPath.read(annotatedJson, "$.customer.id");
                            if (v != null) p.setCustomerId(String.valueOf(v));
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ex) { log.debug("CustomerId fallback failed for case {}", caseInstanceId, ex); }
                try {
                    if (mappings.values().stream().anyMatch(m -> "order_priority".equals(m.plainColumn) || "order_priority".equals(m.column))) {
                        Object v = null;
                        for (var fm : mappings.values()) {
                            if ("order_priority".equals(fm.plainColumn) || "order_priority".equals(fm.column)) {
                                try { v = JsonPath.read(annotatedJson, fm.jsonPath); } catch (Exception ex) { log.debug("JsonPath read failed", ex); }
                                break;
                            }
                        }
                        if (v != null) p.setOrderPriority(String.valueOf(v));
                    }
                } catch (Exception ex) { log.debug("Failed to map order_priority for case {}", caseInstanceId, ex); }
                // Fallback: if orderTotal is still null, try legacy mappings (column -> jsonPath) for common names
                try {
                    if (p.getOrderTotal() == null) {
                        // common historical column: total_amount -> $.total
                        String jp = legacyMappings.get("total_amount");
                        if (jp != null) {
                            try {
                                Object v = JsonPath.read(annotatedJson, jp);
                                if (v instanceof Number) p.setOrderTotal(((Number)v).doubleValue());
                            } catch (Exception ex) { log.debug("JsonPath legacy read failed", ex); }
                        }
                        // final fallback: direct $.total value if available
                        if (p.getOrderTotal() == null && directTotalFallback != null) {
                            p.setOrderTotal(directTotalFallback);
                        }
                    }
                } catch (Exception ex) { log.debug("Legacy mapping fallback failed for case {}", caseInstanceId, ex); }
                // final fallback for priority
                try {
                    if (p.getOrderPriority() == null && directPriorityFallback != null) {
                        p.setOrderPriority(directPriorityFallback);
                    }
                } catch (Exception ex) { log.debug("Priority fallback failed for case {}", caseInstanceId, ex); }
              }, plainRepo);

        } catch (Exception ex) {
            log.error("reindex error for {}", caseInstanceId, ex);
        }
    }
}
