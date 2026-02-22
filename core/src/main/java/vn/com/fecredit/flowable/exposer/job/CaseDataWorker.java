package vn.com.fecredit.flowable.exposer.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import vn.com.fecredit.flowable.exposer.entity.SysExposeRequest;
import vn.com.fecredit.flowable.exposer.repository.SysExposeRequestRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.com.fecredit.flowable.exposer.service.MetadataAnnotator;
import vn.com.fecredit.flowable.exposer.service.MetadataResolver;

// Note: CaseDataWorkerService contains the heavy lifting previously in this class
import vn.com.fecredit.flowable.exposer.job.CaseDataWorkerService;

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

    private final SysExposeRequestRepository reqRepo;
    private final ObjectMapper om;              // retained for legacy tests
    private final CaseDataWorkerService service;

    @org.springframework.beans.factory.annotation.Autowired
    public CaseDataWorker(JdbcTemplate jdbc,
                          ObjectMapper om,
                          MetadataAnnotator annotator,
                          MetadataResolver resolver,
                          SysExposeRequestRepository reqRepo,
                          vn.com.fecredit.flowable.exposer.service.IndexLoader indexLoader) {
        this.reqRepo = reqRepo;
        this.om = om;
        // service encapsulates all reindex and database logic
        this.service = new CaseDataWorkerService(jdbc, resolver, om, annotator, indexLoader);
    }

    /**
     * No-arg constructor used by legacy unit tests. Services and dependencies are
     * initialized to null; callers should avoid using the worker beyond reflective
     * access to helper methods.
     */
    public CaseDataWorker() {
        this(null, null, null, null, null, null);
    }

    @Scheduled(fixedDelay = 1000)
    public void pollAndProcess() {
        try {
            List<SysExposeRequest> pending = reqRepo.findByStatus("PENDING");
            if (pending == null || pending.isEmpty()) {
                log.debug("CaseDataWorker.pollAndProcess - no pending requests");
                return;
            }
            log.info("CaseDataWorker.pollAndProcess - found {} pending requests", pending.size());
            for (SysExposeRequest r : pending) {
                log.info("CaseDataWorker.pollAndProcess - processing request id={} caseInstanceId={}", r.getId(), r.getCaseInstanceId());
                try {
                    service.reindexByCaseInstanceId(r.getCaseInstanceId());
                    r.setStatus("DONE");
                    r.setProcessedAt(java.time.OffsetDateTime.now());
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

    // all remaining logic has been moved to CaseDataWorkerService

    /**
     * Legacy entrypoint kept for consumers that previously invoked this method
     * directly on the worker.
     */
    public void reindexByCaseInstanceId(String caseInstanceId) {
        if (service != null) {
            service.reindexByCaseInstanceId(caseInstanceId);
        }
    }

    // legacy helpers ---------------------------------------------------------

    @SuppressWarnings("unused") // reflective tests
    private Map<String, Object> buildIndexRow(vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition def,
                                             String caseInstanceId,
                                             String jsonForItem) {
        if (om == null) return null;
        return new CaseDataWorkerIndexRowBuilder(om).buildIndexRow(def, caseInstanceId, jsonForItem);
    }

    // determineColumnType already present below

    // ratchet in minimal helpers for legacy tests
    private String determineColumnType(Object value) {
        return determineColumnType(value, null);
    }

    private String determineColumnType(Object value, String hint) {
        if (hint != null && !hint.isBlank()) {
            // allow callers to supply any concrete type; some hints (e.g. DECIMAL)
            // are shorthand and need expansion.  preserve explicit size
            // parameters (VARCHAR(100)) by returning the original string.
            String up = hint.toUpperCase(java.util.Locale.ROOT).trim();
            if (up.equals("DECIMAL")) {
                return "DECIMAL(19,4)";
            }
            if (up.equals("TEXT")) {
                // TEXT is ambiguous; on H2 we prefer CLOB, otherwise LONGTEXT.
                return isH2() ? "CLOB" : "LONGTEXT";
            }
            if (hint.contains("(")) {
                // keep the caller's exact case when they specified a size.
                return hint;
            }
            return up;
        }
        if (value == null) return "VARCHAR(255)";
        if (value instanceof Boolean) return "BOOLEAN";
        if (value instanceof Integer || value instanceof Long) return "BIGINT";
        if (value instanceof Float || value instanceof Double) return "DECIMAL(19,4)";
        if (value instanceof java.time.temporal.Temporal || value instanceof java.util.Date) return "TIMESTAMP";
        String s = value.toString();
        if (s.length() > 1024) return isH2() ? "CLOB" : "LONGTEXT";
        return "VARCHAR(255)";
    }

    // stub for isH2 used by determineColumnType.  made package-visible so
    // unit tests can override it; production code uses the dialect helper instead
    // which is injected into helpers and consulted at runtime.
    boolean isH2() { return false; }
}
