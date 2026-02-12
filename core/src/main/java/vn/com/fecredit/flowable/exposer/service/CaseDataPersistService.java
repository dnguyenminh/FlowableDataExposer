package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.com.fecredit.flowable.exposer.service.MetadataAnnotator;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * Service that persists case blobs in a separate transaction so that process
 * rollbacks do not remove the canonical snapshot. Kept intentionally small
 * and testable.
 */
@Service
public class CaseDataPersistService {
    private static final Logger log = LoggerFactory.getLogger(CaseDataPersistService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final MetadataAnnotator annotator;
    private final CaseDataWriter writer;

    public CaseDataPersistService(JdbcTemplate jdbc, ObjectMapper om, MetadataAnnotator annotator, CaseDataWriter writer) {
        this.jdbc = jdbc;
        this.om = om;
        this.annotator = annotator;
        this.writer = writer;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistSysCaseData(String caseInstanceId, String entityType, String payload) {
        log.info("persistSysCaseData - entering caseInstanceId={} entityType={} payloadLen={}", caseInstanceId, entityType, (payload == null ? 0 : payload.length()));

        String annotatedPayload = annotatePayload(payload, entityType);
        int nextVersion = computeNextVersion(caseInstanceId);

        Timestamp now = new Timestamp(System.currentTimeMillis());
        boolean hasStatus = hasColumn("sys_case_data_store", "status");
        boolean hasError = hasColumn("sys_case_data_store", "error_message");
        boolean hasVer = hasColumn("sys_case_data_store", "version");

        try {
            writer.insertSysCaseData(caseInstanceId, entityType, annotatedPayload, now, hasStatus, hasError, hasVer, nextVersion);
        } catch (Exception e) {
            log.error("persistSysCaseData - insert failed for {}: {}", caseInstanceId, e.getMessage(), e);
            throw e;
        }

        log.info("persisted sys_case_data_store for {} (version={})", caseInstanceId, nextVersion);
        verifyPersistCount(caseInstanceId);
    }

    private String annotatePayload(String payload, String entityType) {
        String annotatedPayload = payload;
        if (payload != null && om != null && annotator != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = om.readValue(payload, Map.class);
                if (m != null) {
                    try { annotator.annotate(m, entityType); } catch (Exception t) { log.debug("Annotator failed during persist: {}", t.getMessage()); }
                    annotatedPayload = om.writeValueAsString(m);
                }
            } catch (Exception ex) {
                log.debug("Could not parse/annotate payload before persist: {}", ex.getMessage());
            }
        }
        return annotatedPayload;
    }

    private int computeNextVersion(String caseInstanceId) {
        Integer currentMax = null;
        try {
            if (hasColumn("sys_case_data_store", "version")) {
                currentMax = jdbc.queryForObject(
                        "SELECT MAX(version) FROM sys_case_data_store WHERE case_instance_id = ?",
                        Integer.class, caseInstanceId);
            }
        } catch (Exception e) {
            log.debug("Could not read current version for {}: {}", caseInstanceId, e.getMessage());
        }
        return (currentMax == null) ? 1 : (currentMax + 1);
    }

    private void verifyPersistCount(String caseInstanceId) {
        try {
            Integer cnt = jdbc.queryForObject("SELECT COUNT(1) FROM sys_case_data_store WHERE case_instance_id = ?", Integer.class, caseInstanceId);
            log.info("verify persistSysCaseData: existing rows for {} = {}", caseInstanceId, cnt);
        } catch (Exception e) {
            log.debug("verify persistSysCaseData: count query failed for {}: {}", caseInstanceId, e.getMessage());
        }
    }

    /**
     * Re-index existing blobs for a given entity type by annotating payloads with @class
     * markers and updating the payload in-place. This is best-effort and executed in a
     * separate transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reindexExistingBlobs(String entityType) {
        try {
            List<Map<String,Object>> rows = jdbc.queryForList("SELECT id, payload FROM sys_case_data_store WHERE entity_type = ?", entityType);
            for (Map<String,Object> row : rows) {
                Object id = row.get("id");
                Object payloadObj = row.get("payload");
                if (payloadObj == null) continue;
                String payload = String.valueOf(payloadObj);
                try {
                    @SuppressWarnings("unchecked")
                    Map<String,Object> m = om.readValue(payload, Map.class);
                    if (m == null) continue;
                    try { annotator.annotate(m, entityType); } catch (Exception t) { log.debug("Annotator failed during reindex for id {}: {}", id, t.getMessage()); }
                    String updated = om.writeValueAsString(m);
                    jdbc.update("UPDATE sys_case_data_store SET payload = ? WHERE id = ?", updated, id);
                } catch (Exception ex) {
                    log.warn("Failed to reindex blob id {}: {}", id, ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("reindexExistingBlobs failed for entityType {}: {}", entityType, e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateCaseInstanceIdForRecent(String newCaseInstanceId, java.time.Duration lookback) {
        try {
            java.time.Instant cutoff = java.time.Instant.now().minus(lookback == null ? java.time.Duration.ofSeconds(5) : lookback);
            java.sql.Timestamp cutoffTs = java.sql.Timestamp.from(cutoff);
            String sql = "UPDATE sys_case_data_store SET case_instance_id = ? WHERE created_at >= ? AND case_instance_id <> ?";
            int updated = jdbc.update(sql, newCaseInstanceId, cutoffTs, newCaseInstanceId);
            log.info("updateCaseInstanceIdForRecent: updated {} rows to caseInstanceId={}", updated, newCaseInstanceId);
        } catch (Throwable t) {
            log.warn("updateCaseInstanceIdForRecent failed for {}", newCaseInstanceId, t);
        }
    }

    private boolean hasColumn(String tableName, String columnName) {
        try (var conn = jdbc.getDataSource().getConnection()) {
            var meta = conn.getMetaData();
            try (var rs = meta.getColumns(null, null, tableName.toUpperCase(), columnName.toUpperCase())) {
                return rs.next();
            }
        } catch (Exception e) {
            log.debug("hasColumn check failed for {}.{}: {}", tableName, columnName, e.getMessage());
            return false;
        }
    }
}
