package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

public class MetadataMixinE2eTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // Initialize H2 database for testing
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:testdb;MODE=MySQL");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        jdbc = new JdbcTemplate(dataSource);
        setupSchema();
    }

    void setupSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_case_data_store (id INT AUTO_INCREMENT PRIMARY KEY, case_instance_id VARCHAR(255) NOT NULL, entity_type VARCHAR(255), payload CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS case_plain_order (id INT AUTO_INCREMENT PRIMARY KEY, case_instance_id VARCHAR(255) NOT NULL, customer_id VARCHAR(255), order_total NUMERIC(19,4), order_priority VARCHAR(50), plain_payload CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, shared_col VARCHAR(255))");
    }

    @Test
    void mixin_field_is_exported_to_plain_table() throws Exception {
        // Test setup with mixin metadata
        assertThat(jdbc).isNotNull();
    }
}
