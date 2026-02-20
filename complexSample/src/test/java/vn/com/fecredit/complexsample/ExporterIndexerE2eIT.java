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
    void exporter_and_indexer_end_to_end() {
        ensureSchema();
        String caseInstanceId = startOrderCase(null);

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

        // trigger reindex endpoint (production-like)
        try { rest.postForEntity("http://localhost:" + port + "/api/orders/" + caseInstanceId + "/reindex", null, Void.class); } catch (Exception ignored) {}

        // wait for plain table to be populated by the indexer
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Integer cnt = jdbc.queryForObject("SELECT count(*) FROM case_plain_order WHERE case_instance_id = ?", Integer.class, caseInstanceId);
                    assertThat(cnt).isNotNull().isGreaterThan(0);
                });

        // verify some expected index columns exist and are readable
        String payload = jdbc.queryForObject("SELECT plain_payload FROM case_plain_order WHERE case_instance_id = ?", String.class, caseInstanceId);
        assertThat(payload).isNotNull();
        assertThat(payload).contains("\"orderId\"");
    }

    void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_case_data_store (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "case_instance_id VARCHAR(255) NOT NULL, " +
                "entity_type VARCHAR(255), " +
                "payload CLOB, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "version INT DEFAULT 1)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS case_plain_order (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "case_instance_id VARCHAR(255) NOT NULL, " +
                "customer_id VARCHAR(255), " +
                "order_total NUMERIC(19,4), " +
                "order_priority VARCHAR(50), " +
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

    String startOrderCase(Object payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = payload == null ? "{\"orderId\":\"12345\",\"customer\":{\"id\":\"CUST01\"},\"total\":1234.56}" : payload.toString();
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = rest.postForEntity("http://localhost:" + port + "/api/orders", request, Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String,Object> respBody = response.getBody();
        assertThat(respBody).isNotNull().containsKey("id");
        String caseId = String.valueOf(respBody.get("id"));
        return caseId;
    }
}
