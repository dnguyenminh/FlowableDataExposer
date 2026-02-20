package vn.com.fecredit.flowable.exposer.job;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CaseDataWorkerH2FallbackTest {

    @Test
    void h2_select_update_insert_fallback_inserts_then_updates_without_merge() throws Exception {
        // Setup H2 datasource
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:testdb_h2fallback;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        CaseDataWorker worker = new CaseDataWorker();

        // inject jdbc into private field
        java.lang.reflect.Field f = CaseDataWorker.class.getDeclaredField("jdbc");
        f.setAccessible(true);
        f.set(worker, jdbc);

        // build a row
        Map<String, Object> r = new HashMap<>();
        r.put("case_instance_id", "c100");
        r.put("sku", "S100");
        r.put("qty", 1);

        // let core create the index table using its createDefaultWorkTable logic
        java.lang.reflect.Method create = CaseDataWorker.class.getDeclaredMethod("createDefaultWorkTable", String.class, java.util.Map.class);
        create.setAccessible(true);
        create.invoke(worker, "item_index", r);

        // call private h2 fallback directly
        java.lang.reflect.Method m = CaseDataWorker.class.getDeclaredMethod("h2SelectUpdateInsert", String.class, java.util.List.class, java.util.Map.class);
        m.setAccessible(true);
        m.invoke(worker, "item_index", java.util.List.of("case_instance_id","sku","qty"), r);

        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM item_index WHERE case_instance_id = ?", Integer.class, "c100");
        assertThat(cnt).isEqualTo(1);

        // call again with updated qty -> should perform UPDATE path
        r.put("qty", 7);
        m.invoke(worker, "item_index", java.util.List.of("case_instance_id","sku","qty"), r);

        Integer qty = jdbc.queryForObject("SELECT qty FROM item_index WHERE case_instance_id = ?", Integer.class, "c100");
        assertThat(qty).isEqualTo(7);
    }
}
