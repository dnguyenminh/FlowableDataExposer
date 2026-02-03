package vn.com.fecredit.flowable.exposer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;

/**
 * Service that persists case blobs in a separate transaction so that process
 * rollbacks do not remove the canonical snapshot. Kept intentionally small
 * and testable.
 */
@Service
public class CaseDataPersistService {
    private static final Logger log = LoggerFactory.getLogger(CaseDataPersistService.class);

    private final JdbcTemplate jdbc;

    public CaseDataPersistService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistSysCaseData(String caseInstanceId, String entityType, String payload) {
        // Compute next version for this case: max(version)+1 or 1 if none
        Integer currentMax = null;
        try {
            // only attempt version read if the column exists
            if (hasColumn("sys_case_data_store", "version")) {
                currentMax = jdbc.queryForObject(
                        "SELECT MAX(version) FROM sys_case_data_store WHERE case_instance_id = ?",
                        Integer.class, caseInstanceId);
            }
        } catch (Exception e) {
            // If the column/table doesn't exist yet, treat as new row
            log.debug("Could not read current version for {}: {}", caseInstanceId, e.getMessage());
        }
        int nextVersion = (currentMax == null) ? 1 : (currentMax + 1);

        // Bind the created_at explicitly
        Timestamp now = new Timestamp(System.currentTimeMillis());
        // choose insert form depending on whether new columns exist (supports older DB files)
        boolean hasStatus = hasColumn("sys_case_data_store", "status");
        boolean hasError = hasColumn("sys_case_data_store", "error_message");
        boolean hasVer = hasColumn("sys_case_data_store", "version");

        if (hasStatus || hasError || hasVer) {
            String sql = "INSERT INTO sys_case_data_store(case_instance_id, entity_type, payload, created_at"
                    + (hasStatus ? ", status" : "")
                    + (hasError ? ", error_message" : "")
                    + (hasVer ? ", version" : "")
                    + ") VALUES (?,?,?,?"
                    + (hasStatus ? ",?" : "")
                    + (hasError ? ",?" : "")
                    + (hasVer ? ",?" : "")
                    + ")";
            // assemble params in order
            java.util.List<Object> params = new java.util.ArrayList<>();
            params.add(caseInstanceId);
            params.add(entityType);
            params.add(payload);
            params.add(now);
            if (hasStatus) params.add("PENDING");
            if (hasError) params.add(null);
            if (hasVer) params.add(nextVersion);
            log.debug("Executing SQL: {} params={} (payload length={})", sql, params, (payload == null ? 0 : payload.length()));
            jdbc.update(sql, params.toArray());
        } else {
            // fallback to minimal insert when DB schema is old
            String sql = "INSERT INTO sys_case_data_store(case_instance_id, entity_type, payload, created_at) VALUES (?,?,?,?)";
            log.debug("Executing legacy SQL: {} params=[{}, {}, <payload length:{}>, {}]", sql,
                    caseInstanceId, entityType, (payload == null ? 0 : payload.length()), now);
            jdbc.update(sql, caseInstanceId, entityType, payload, now);
        }
        log.debug("persisted sys_case_data_store for {} (version={})", caseInstanceId, nextVersion);
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
