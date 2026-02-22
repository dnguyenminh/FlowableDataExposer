package vn.com.fecredit.complexsample;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = ComplexSampleTestApplication.class)
public class ExporterIndexerE2eIT {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void indexer_populates_index_tables_from_blob_store() {
        // start case with payload that exercises multiple indices (order, items, params)
        String payload = "{\"order_total\":1234.56,\"customer\":{\"id\":\"CUST01\"},\"meta\":{\"priority\":\"HIGH\"},\"items\":[{\"id\":\"I1\",\"sku\":\"S1\",\"price\":9.99}],\"params\":{\"color\":\"red\"}}";
        String caseInstanceId = startOrderCase(payload);

        // wait for blob persisted
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM sys_case_data_store WHERE case_instance_id = ?", Integer.class, caseInstanceId);
                        return cnt != null && cnt > 0;
                    } catch (Exception e) {
                        return false;
                    }
                });

        // Trigger reindex endpoint (production-like)
        try { rest.postForEntity("http://localhost:" + port + "/api/orders/" + caseInstanceId + "/reindex", null, Void.class); } catch (Exception ignored) {}

        // Debug: dump index tables immediately after triggering reindex to assist diagnosis
        try {
            System.out.println("DEBUG[TEST]: DUMP AFTER TRIGGER - order_index rows:");
            java.util.List<java.util.Map<String, Object>> orderRows = jdbc.queryForList("SELECT * FROM order_index WHERE case_instance_id = ?", caseInstanceId);
            for (java.util.Map<String, Object> r : orderRows) System.out.println("  " + r);

            System.out.println("DEBUG[TEST]: DUMP AFTER TRIGGER - item_index rows:");
            java.util.List<java.util.Map<String, Object>> itemRows = jdbc.queryForList("SELECT * FROM item_index WHERE case_instance_id = ?", caseInstanceId);
            for (java.util.Map<String, Object> r : itemRows) System.out.println("  " + r);

            System.out.println("DEBUG[TEST]: DUMP AFTER TRIGGER - params_index rows:");
            java.util.List<java.util.Map<String, Object>> paramsRows = jdbc.queryForList("SELECT * FROM params_index WHERE case_instance_id = ?", caseInstanceId);
            for (java.util.Map<String, Object> r : paramsRows) System.out.println("  " + r);
        } catch (Exception e) {
            System.out.println("DEBUG[TEST]: dump failed after trigger: " + e.getMessage());
        }

        // wait for order_index to be populated with non-null mapped values
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        BigDecimal orderTotal = jdbc.queryForObject("SELECT order_total FROM order_index WHERE case_instance_id = ? ORDER BY created_at DESC LIMIT 1", BigDecimal.class, caseInstanceId);
                        String customerId = jdbc.queryForObject("SELECT customer_id FROM order_index WHERE case_instance_id = ? ORDER BY created_at DESC LIMIT 1", String.class, caseInstanceId);
                        String priority = jdbc.queryForObject("SELECT priority FROM order_index WHERE case_instance_id = ? ORDER BY created_at DESC LIMIT 1", String.class, caseInstanceId);
                        return orderTotal != null && "CUST01".equals(customerId) && "HIGH".equals(priority);
                    } catch (Exception e) {
                        return false;
                    }
                });

        // Debug: dump ORDER_INDEX schema to help diagnose missing columns (use JDBC metadata to avoid INFORMATION_SCHEMA param issues)
        try (java.sql.Connection conn = jdbc.getDataSource().getConnection()) {
            System.out.println("DEBUG[TEST]: ORDER_INDEX schema (via JDBC metadata):");
            try (java.sql.ResultSet rs = conn.getMetaData().getColumns(null, null, "ORDER_INDEX", null)) {
                while (rs.next()) {
                    System.out.println("  col=" + rs.getString("COLUMN_NAME") + " type=" + rs.getString("TYPE_NAME"));
                }
            }
        } catch (Exception e) {
            System.out.println("DEBUG[TEST]: failed to dump ORDER_INDEX schema: " + e.getMessage());
        }

        // verify indexed columns values match payload (pick latest row if multiple exist)
        BigDecimal orderTotal = jdbc.queryForObject("SELECT order_total FROM order_index WHERE case_instance_id = ? ORDER BY created_at DESC LIMIT 1", BigDecimal.class, caseInstanceId);
        String customerId = jdbc.queryForObject("SELECT customer_id FROM order_index WHERE case_instance_id = ? ORDER BY created_at DESC LIMIT 1", String.class, caseInstanceId);
        String priority = jdbc.queryForObject("SELECT priority FROM order_index WHERE case_instance_id = ? ORDER BY created_at DESC LIMIT 1", String.class, caseInstanceId);

        assertThat(orderTotal).isNotNull().isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(customerId).isEqualTo("CUST01");
        assertThat(priority).isEqualTo("HIGH");

        // wait for item_index to be populated and verify
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM item_index WHERE case_instance_id = ?", Integer.class, caseInstanceId);
                        if (cnt == null || cnt <= 0) return false;
                        String sku = jdbc.queryForObject("SELECT sku FROM item_index WHERE case_instance_id = ?", String.class, caseInstanceId);
                        BigDecimal price = jdbc.queryForObject("SELECT price FROM item_index WHERE case_instance_id = ?", BigDecimal.class, caseInstanceId);
                        return "S1".equals(sku) && new BigDecimal("9.99").compareTo(price) == 0;
                    } catch (Exception e) {
                        return false;
                    }
                });

        // wait for params_index to be populated and verify
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM params_index WHERE case_instance_id = ?", Integer.class, caseInstanceId);
                        if (cnt == null || cnt <= 0) return false;
                        String key = jdbc.queryForObject("SELECT param_key FROM params_index WHERE case_instance_id = ?", String.class, caseInstanceId);
                        String val = jdbc.queryForObject("SELECT param_value FROM params_index WHERE case_instance_id = ?", String.class, caseInstanceId);
                        return "color".equals(key) && "red".equals(val);
                    } catch (Exception e) {
                        return false;
                    }
                });
    }

    String startOrderCase(String payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = payload == null ? "{\"order_total\":1234.56,\"customer\":{\"id\":\"CUST01\"}}" : payload;
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = rest.postForEntity("http://localhost:" + port + "/api/orders", request, Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String,Object> respBody = response.getBody();
        assertThat(respBody).isNotNull().containsKey("id");
        return String.valueOf(respBody.get("id"));
    }
}
