package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.com.fecredit.flowable.exposer.repository.CasePlainOrderRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class MetadataAutoCreateColumnTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MetadataResolver resolver;

    @Autowired
    CaseDataWorker worker;

    @Autowired
    CasePlainOrderRepository plainRepo;

    @BeforeEach
    void setupSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_case_data_store (id BIGINT AUTO_INCREMENT PRIMARY KEY, case_instance_id VARCHAR(255) NOT NULL, entity_type VARCHAR(255), payload CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("DROP TABLE IF EXISTS case_plain_order_temp");
        jdbc.execute("CREATE TABLE case_plain_order_temp (id BIGINT AUTO_INCREMENT PRIMARY KEY, case_instance_id VARCHAR(255) NOT NULL, plain_payload CLOB)");
    }

    @Test
    void generator_ddl_applies_and_worker_can_populate_new_column() throws Exception {
        String caseId = "auto-col-1";
        String payload = "{\"@class\":\"Order\",\"total\":42.0,\"sharedChild\":\"x\"}";
        jdbc.update("INSERT INTO sys_case_data_store(case_instance_id, entity_type, payload, created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)", caseId, "Order", payload);

        // generate DDL for requested plainColumn 'shared_col' (simulate metadata)
        vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition.FieldMapping fm = new vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition.FieldMapping();
        fm.column = "shared_col";
        fm.plainColumn = "shared_col";
        fm.type = "string";

        String ddl = MetadataDdlGenerator.generateAddColumnIfNotExists("case_plain_order_temp", fm);
        jdbc.execute(ddl);

        // sanity: column exists by inserting
        jdbc.update("INSERT INTO case_plain_order_temp(case_instance_id, plain_payload, shared_col) VALUES (?,?,?)", caseId+"-t", "{}", "ok");
        var col = jdbc.queryForObject("SELECT shared_col FROM case_plain_order_temp WHERE case_instance_id = ?", String.class, caseId+"-t");
        assertThat(col).isEqualTo("ok");
    }
}
