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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = ComplexSampleTestApplication.class, properties = {
        "spring.task.scheduling.enabled=false",
        "flowable.job-executor-activate=false"
})
public class ExporterIndexerIdempotentE2eIT {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void reindex_is_idempotent_when_called_multiple_times() {
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

        // Trigger reindex twice (simulate duplicate requests)
        try { rest.postForEntity("http://localhost:" + port + "/api/orders/" + caseInstanceId + "/reindex", null, Void.class); } catch (Exception ignored) {}
        try { Thread.sleep(250); } catch (InterruptedException ignored) {}
        try { rest.postForEntity("http://localhost:" + port + "/api/orders/" + caseInstanceId + "/reindex", null, Void.class); } catch (Exception ignored) {}

        // Wait for order_index to be present
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM order_index WHERE case_instance_id = ?", Integer.class, caseInstanceId);
                        return cnt != null && cnt > 0;
                    } catch (Exception e) {
                        return false;
                    }
                });

        // After the index exists, assert that repeated reindexing didn't create multiple order_index rows for the same case (idempotent upsert behaviour)
        Integer finalCount = jdbc.queryForObject("SELECT count(*) FROM order_index WHERE case_instance_id = ?", Integer.class, caseInstanceId);
        assertThat(finalCount).isNotNull().isEqualTo(1);

        // verify values
        java.math.BigDecimal orderTotal = jdbc.queryForObject("SELECT order_total FROM order_index WHERE case_instance_id = ? LIMIT 1", java.math.BigDecimal.class, caseInstanceId);
        String customerId = jdbc.queryForObject("SELECT customer_id FROM order_index WHERE case_instance_id = ? LIMIT 1", String.class, caseInstanceId);
        assertThat(orderTotal).isNotNull();
        assertThat(customerId).isEqualTo("CUST01");
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
