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
import vn.com.fecredit.flowable.exposer.service.MetadataResolver;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition.FieldMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = ComplexSampleTestApplication.class)
public class ExposeMappingE2eIT {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    org.springframework.context.ApplicationContext ctx;

    @Test
    void startBpmnAndVerifyPlainTable() throws Exception {
        ensureSchema();
        final String ORDER_ID = "12345";
        final String CUSTOMER_ID = "CUST01";
        final double TOTAL = 1234.56;

        Map<String,Object> req = Map.of("orderId", ORDER_ID, "customer", Map.of("id", CUSTOMER_ID), "total", TOTAL);
        String body = toJson(req);
        String caseInstanceId = startOrderProcess(body);

        // wait for blob persisted
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM sys_case_data_store WHERE case_instance_id = ?", Integer.class, caseInstanceId);
                        return cnt != null && cnt > 0;
                    } catch (Exception e) { return false; }
                });

        // Rely on background worker to process events asynchronously. Do NOT trigger the worker
        // or reindex endpoint manually in this end-to-end test; the Awaitility below will
        // observe whether the system processed the case and populated the plain table.

        // wait for plain table and assert row values
        verifyPlainTableForCase(caseInstanceId, TOTAL, CUSTOMER_ID);
    }

    @Test
    void startCmmnAndVerifyPlainTable() throws Exception {
        ensureSchema();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String,Object> req = Map.of("orderId","12345", "customer", Map.of("id","CUST01"), "total", 1234.56);
        String body = toJson(req);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = rest.postForEntity("http://localhost:" + port + "/api/orders?type=cmmn", request, Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String,Object> respBody = response.getBody();
        assertThat(respBody).isNotNull().containsKey("id");
        String caseInstanceId = String.valueOf(respBody.get("id"));

        // wait for blob persisted
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM sys_case_data_store WHERE case_instance_id = ?", Integer.class, caseInstanceId);
                        return cnt != null && cnt > 0;
                    } catch (Exception e) { return false; }
                });

        // wait for plain table and assert row values
        verifyPlainTableForCase(caseInstanceId, 1234.56, "CUST01");
    }

    void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS case_plain_order (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "case_instance_id VARCHAR(255) NOT NULL, " +
                "plain_payload CLOB, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_expose_requests (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "case_instance_id VARCHAR(255) NOT NULL, " +
                "entity_type VARCHAR(255), " +
                "requested_by VARCHAR(255), " +
                "requested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "status VARCHAR(50) DEFAULT 'PENDING', " +
                "processed_at TIMESTAMP NULL)");
        try { jdbc.execute("CREATE UNIQUE INDEX ux_case_plain_order_case_instance_id ON case_plain_order(case_instance_id)"); } catch (Exception ignored) {}
    }

    String startOrderProcess(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = rest.postForEntity("http://localhost:" + port + "/api/orders", request, Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String,Object> respBody = response.getBody();
        assertThat(respBody).isNotNull().containsKey("id");
        return String.valueOf(respBody.get("id"));
    }

    private void verifyPlainTableForCase(String caseInstanceId, double total, String customerId) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    System.out.println("DEBUG: checking caseInstanceId=" + caseInstanceId);
                    Integer cnt = jdbc.queryForObject("SELECT count(*) FROM case_plain_order WHERE case_instance_id = ?", Integer.class, caseInstanceId);
                    System.out.println("DEBUG: count for case_instance_id=" + cnt);
                    System.out.println("DEBUG: sys_expose_requests rows:");
                    try { jdbc.queryForList("SELECT * FROM sys_expose_requests").forEach(r -> System.out.println(r)); } catch (Exception ignored) {}
                    System.out.println("DEBUG: case_plain_order rows:");
                    try { jdbc.queryForList("SELECT * FROM case_plain_order").forEach(r -> System.out.println(r)); } catch (Exception ignored) {}
                    assertThat(cnt).isNotNull().isGreaterThan(0);

                    // Resolve effective mappings at runtime (use MetadataResolver to match worker behavior)
                    var resolver = ctx.getBean(MetadataResolver.class);
                    Map<String, FieldMapping> effective = resolver.mappingsMetadataFor("Order");
                    String payload = jdbc.queryForObject("SELECT plain_payload FROM case_plain_order WHERE case_instance_id = ?", String.class, caseInstanceId);
                    assertThat(payload).isNotNull();
                    for (var entry : effective.entrySet()) {
                        var fm = entry.getValue();
                        if (fm == null || fm.jsonPath == null) continue;

                        // only consider mappings that target the plain table
                        boolean exportsToPlain = Boolean.TRUE.equals(fm.exportToPlain)
                                || (fm.exportDest != null && fm.exportDest.contains("plain"))
                                || (fm.plainColumn != null && !fm.plainColumn.isBlank());
                        if (!exportsToPlain) continue;

                        String col = fm.plainColumn != null && !fm.plainColumn.isBlank() ? fm.plainColumn : fm.column;
                        if (col == null || col.isBlank()) continue;

                        // ensure column exists
                        Integer colCount = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                                Integer.class, "CASE_PLAIN_ORDER", col.toUpperCase());
                        assertThat(colCount).isNotNull().isGreaterThan(0);

                        Object jpVal = JsonPath.read(payload, fm.jsonPath);
                        Object val = jdbc.queryForObject(String.format("SELECT %s FROM case_plain_order WHERE case_instance_id = ?", col), Object.class, caseInstanceId);
                        assertThat(val).isEqualTo(jpVal);

                        // additional sanity checks for known fields
                        if ("$.total".equals(fm.jsonPath) || fm.jsonPath.endsWith(".total") || fm.plainColumn != null && fm.plainColumn.toLowerCase().contains("total")) {
                            BigDecimal bd = val instanceof BigDecimal ? (BigDecimal) val : new BigDecimal(val.toString());
                            assertThat(bd.doubleValue()).isEqualTo(total);
                        }
                        if ("$.customer.id".equals(fm.jsonPath) || fm.jsonPath.endsWith("customer.id") || fm.plainColumn != null && fm.plainColumn.toLowerCase().contains("customer")) {
                            assertThat(String.valueOf(val)).isEqualTo(customerId);
                        }
                    }
                });
    }

    private static String toJson(Map<String,Object> m) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
