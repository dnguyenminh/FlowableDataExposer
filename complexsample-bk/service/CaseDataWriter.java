package vn.com.fecredit.complexsample.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Component
public class CaseDataWriter {
    private static final Logger log = LoggerFactory.getLogger(CaseDataWriter.class);

    private final JdbcTemplate jdbc;

    public CaseDataWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertSysCaseData(String caseInstanceId, String entityType, String annotatedPayload,
                                  Timestamp now, boolean hasStatus, boolean hasError, boolean hasVer, int nextVersion) {
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
            List<Object> params = new ArrayList<>();
            params.add(caseInstanceId);
            params.add(entityType);
            params.add(annotatedPayload);
            params.add(now);
            if (hasStatus) params.add("PENDING");
            if (hasError) params.add(null);
            if (hasVer) params.add(nextVersion);
            log.info("Executing SQL: {} params={} (payload length={})", sql, params, (annotatedPayload == null ? 0 : annotatedPayload.length()));
            try {
                jdbc.update(sql, params.toArray());
            } catch (Exception e) {
                log.error("insertSysCaseData - insert failed for {}: {}", caseInstanceId, e.getMessage(), e);
                throw e;
            }
        } else {
            String sql = "INSERT INTO sys_case_data_store(case_instance_id, entity_type, payload, created_at) VALUES (?,?,?,?)";
            log.info("Executing legacy SQL: {} params=[{}, {}, <payload length:{}>, {}]", sql,
                    caseInstanceId, entityType, (annotatedPayload == null ? 0 : annotatedPayload.length()), now);
            try {
                jdbc.update(sql, caseInstanceId, entityType, annotatedPayload, now);
            } catch (Exception e) {
                log.error("insertSysCaseData - legacy insert failed for {}: {}", caseInstanceId, e.getMessage(), e);
                throw e;
            }
        }
    }
}
