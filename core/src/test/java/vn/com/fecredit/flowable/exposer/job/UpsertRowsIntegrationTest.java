package vn.com.fecredit.flowable.exposer.job;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpsertRowsIntegrationTest {

    @Test
    void upsert_rows_creates_table_and_inserts_rows() throws Exception {
        // Setup lightweight H2 datasource
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:testdb_upsert;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // create service with minimal dependencies (resolver/om/annotator/indexLoader not needed for these helpers)
        CaseDataWorkerService svc = new CaseDataWorkerService(jdbc, null, null, null, null);
        java.lang.reflect.Field f = CaseDataWorkerService.class.getDeclaredField("db");
        f.setAccessible(true);
        Object dbHelper = f.get(svc);

        // build two sample rows
        Map<String, Object> r1 = new HashMap<>();
        r1.put("case_instance_id", "c1");
        r1.put("id", "I1");
        r1.put("sku", "S1");

        Map<String, Object> r2 = new HashMap<>();
        r2.put("case_instance_id", "c2");
        r2.put("id", "I2");
        r2.put("sku", "S2");

        // call private method via reflection
        // ensure table is created first (createDefaultWorkTable is private)
        java.lang.reflect.Method create = dbHelper.getClass().getDeclaredMethod("createDefaultWorkTable", String.class, java.util.Map.class);
        create.setAccessible(true);
        create.invoke(dbHelper, "item_index", r1);

        java.lang.reflect.Method upsert = dbHelper.getClass().getDeclaredMethod("upsertRowsByMetadata", String.class, java.util.List.class, vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition.class);
        upsert.setAccessible(true);
        upsert.invoke(dbHelper, "item_index", List.of(r1, r2), null);

        // verify table exists and rows inserted
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM item_index", Integer.class);
        assertThat(count).isEqualTo(2);

        List<Map<String,Object>> rows = jdbc.queryForList("SELECT case_instance_id, id, sku FROM item_index ORDER BY case_instance_id");
        assertThat(rows.get(0).get("CASE_INSTANCE_ID")).isEqualTo("c1");
        assertThat(rows.get(0).get("ID")).isEqualTo("I1");
    }

    @Test
    void h2_case_sensitive_identifiers_are_uppercased() throws Exception {
        // ensure createDefaultWorkTable produces uppercase column names when H2 is in use
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:testdb_case;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        CaseDataWorkerService svc = new CaseDataWorkerService(jdbc, null, null, null, null);
        java.lang.reflect.Field f = CaseDataWorkerService.class.getDeclaredField("db");
        f.setAccessible(true);
        Object dbHelper = f.get(svc);

        Map<String, Object> row = new HashMap<>();
        row.put("case_instance_id", "C1");

        java.lang.reflect.Method create = dbHelper.getClass().getDeclaredMethod("createDefaultWorkTable", String.class, java.util.Map.class);
        create.setAccessible(true);
        create.invoke(dbHelper, "sample_table", row);

        // verify metadata from H2 shows uppercase names
        java.sql.Connection conn = ds.getConnection();
        try (java.sql.ResultSet rs = conn.getMetaData().getColumns(null, null, "SAMPLE_TABLE", null)) {
            boolean found = false;
            while (rs.next()) {
                String colName = rs.getString("COLUMN_NAME");
                if ("CASE_INSTANCE_ID".equals(colName)) {
                    found = true;
                    break;
                }
            }
            assertThat(found).isTrue();
        }
    }
}
