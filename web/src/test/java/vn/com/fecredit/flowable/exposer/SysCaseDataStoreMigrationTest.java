package vn.com.fecredit.flowable.exposer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class SysCaseDataStoreMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void sysCaseDataStore_table_exists_after_startup() {
        Integer cnt = jdbc.queryForObject(
                "SELECT count(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'SYS_CASE_DATA_STORE'",
                Integer.class);
        assertThat(cnt).isNotNull().isGreaterThan(0);
    }
}
