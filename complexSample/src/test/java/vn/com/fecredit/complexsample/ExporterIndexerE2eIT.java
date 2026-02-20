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
        // start case with payload that the indexer expects
        String payload = "{\"order_total\":1234.56,\"customer\":{\"id\":\"CUST01\"},\"meta\":{\"priority\":\"HIGH\"}}";
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

        // wait for order_index to be populated
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Integer cnt = jdbc.queryForObject("SELECT count(*) FROM order_index WHERE case_instance_id = ?", Integer.class, caseInstanceId);
                    assertThat(cnt).isNotNull().isGreaterThan(0);
                });

        // verify indexed columns values match payload
        BigDecimal orderTotal = jdbc.queryForObject("SELECT order_total FROM order_index WHERE case_instance_id = ?", BigDecimal.class, caseInstanceId);
        String customerId = jdbc.queryForObject("SELECT customer_id FROM order_index WHERE case_instance_id = ?", String.class, caseInstanceId);
        String priority = jdbc.queryForObject("SELECT priority FROM order_index WHERE case_instance_id = ?", String.class, caseInstanceId);

        assertThat(orderTotal).isNotNull().isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(customerId).isEqualTo("CUST01");
        assertThat(priority).isEqualTo("HIGH");
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
