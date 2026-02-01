package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.com.fecredit.flowable.exposer.repository.CasePlainOrderRepository;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class CaseDataWorkerTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CaseDataWorker worker;

    @Autowired
    CasePlainOrderRepository plainRepo;

    @BeforeEach
    void setupSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_case_data_store (id BIGINT AUTO_INCREMENT PRIMARY KEY, case_instance_id VARCHAR(255) NOT NULL, entity_type VARCHAR(255), payload CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS case_plain_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, case_instance_id VARCHAR(255) NOT NULL, customer_id VARCHAR(255), order_total NUMERIC(19,4), order_priority VARCHAR(50), plain_payload CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    }

    @Test
    void reindexByCaseInstanceId_creates_plain_row() throws Exception {
        String caseId = "test-case-1";
        // include @class markers for nested objects so payload aligns with metadata mapping that expects class names
        String payload = "{\"@class\":\"Order\",\"total\":314.15,\"meta\":{\"@class\":\"Meta\",\"priority\":\"HIGH\"},\"customer\":{\"@class\":\"Customer\",\"id\":\"C-e2e-1\"}}";
        jdbc.update("INSERT INTO sys_case_data_store(case_instance_id, entity_type, payload, created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)", caseId, "Order", payload);

        worker.reindexByCaseInstanceId(caseId);

        var opt = plainRepo.findByCaseInstanceId(caseId);
        assertThat(opt).isPresent();
        var p = opt.get();
        assertThat(p.getOrderTotal()).isEqualTo(314.15);
        assertThat(p.getOrderPriority()).isEqualTo("HIGH");
    }
}
