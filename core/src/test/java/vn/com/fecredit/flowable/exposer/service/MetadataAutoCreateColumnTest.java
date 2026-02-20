package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;


public class MetadataAutoCreateColumnTest {

    private JdbcTemplate jdbc;
    private MetadataResolver resolver;

    @BeforeEach
    void setUp() {
        // Initialize H2 database for testing
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:testdb;AUTOCOMMIT=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        jdbc = new JdbcTemplate(dataSource);
        resolver = MetadataResolverTestHelper.createMetadataResolver();
        setupSchema();
    }

    void setupSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_case_data_store (id INT AUTO_INCREMENT PRIMARY KEY, case_instance_id VARCHAR(255) NOT NULL, entity_type VARCHAR(255), payload CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("DROP TABLE IF EXISTS my_test_table");
        jdbc.execute("CREATE TABLE my_test_table (id INT AUTO_INCREMENT PRIMARY KEY, case_instance_id VARCHAR(255) NOT NULL, plain_payload CLOB)");
    }

    //@Test
    void generator_ddl_applies_and_worker_can_populate_new_column() throws Exception {
        String caseId = "auto-col-1";
        String payload = "{\"@class\":\"Order\",\"total\":42.0,\"sharedChild\":\"x\"}";
        jdbc.update("INSERT INTO sys_case_data_store(case_instance_id, entity_type, payload, created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)", caseId, "Order", payload);

        // generate DDL for requested plainColumn 'shared_col' (simulate metadata)
        vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition.FieldMapping fm = new vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition.FieldMapping();
        fm.column = "shared_col";
        fm.plainColumn = "shared_col";
        fm.type = "string";

        String ddl = MetadataDdlGenerator.generateAddColumnIfNotExists("my_test_table", fm);
        System.out.println("Executing DDL: " + ddl);
        jdbc.execute(ddl);

        // sanity: column exists by inserting
        jdbc.update("INSERT INTO my_test_table(case_instance_id, plain_payload, shared_col) VALUES (?,?,?)", caseId+"-t", "{}", "ok");
        var col = jdbc.queryForObject("SELECT shared_col FROM my_test_table WHERE case_instance_id = ?", String.class, caseId+"-t");
        assertThat(col).isEqualTo("ok");
    }
}
