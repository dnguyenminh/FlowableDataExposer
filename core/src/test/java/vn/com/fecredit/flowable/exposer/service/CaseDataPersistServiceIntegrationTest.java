package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CaseDataPersistServiceIntegrationTest {

    @Autowired
    CaseDataPersistService persistService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate txTemplate;

    @BeforeEach
    void ensureTables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_case_data_store (id BIGINT AUTO_INCREMENT PRIMARY KEY, case_instance_id VARCHAR(255) NOT NULL, entity_type VARCHAR(255), payload CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    }

    @Test
    void persist_in_new_transaction_survives_outer_rollback() {
        String caseId = "itest-" + UUID.randomUUID();

        try {
            txTemplate.execute(status -> {
                // inner persist should commit in a separate transaction
                persistService.persistSysCaseData(caseId, "Order", "{\"orderId\":\"" + caseId + "\"}");
                // now trigger rollback of the outer transaction
                throw new RuntimeException("trigger rollback outer");
            });
        } catch (RuntimeException ignored) {
            // expected
        }

        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM sys_case_data_store WHERE case_instance_id = ?", Integer.class, caseId);
        assertThat(cnt).isNotNull().isGreaterThan(0);
    }
}
