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
public class ExporterIndexerMetaE2eIT {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void indexes_meta_and_related_indices() {
        String payload = "{\"order_total\":500.00,\"customer\":{\"id\":\"CUST99\"},\"meta\":{\"priority\":\"LOW\"},\"approvalDecision\":{\"status\":\"APPROVED\"}}";
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

        // Trigger reindex
        try { rest.postForEntity("http://localhost:" + port + "/api/orders/" + caseInstanceId + "/reindex", null, Void.class); } catch (Exception ignored) {}

        // wait for meta_index
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM meta_index WHERE case_instance_id = ?", Integer.class, caseInstanceId);
                        return cnt != null && cnt > 0;
                    } catch (Exception e) { return false; }
                });

        // wait for approval_decision_index
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM approval_decision_index WHERE case_instance_id = ?", Integer.class, caseInstanceId);
                        return cnt != null && cnt > 0;
                    } catch (Exception e) { return false; }
                });

        // verify values
        String priority = jdbc.queryForObject("SELECT priority FROM meta_index WHERE case_instance_id = ? LIMIT 1", String.class, caseInstanceId);
        String approvalStatus = jdbc.queryForObject("SELECT status FROM approval_decision_index WHERE case_instance_id = ? LIMIT 1", String.class, caseInstanceId);
        String customerId = jdbc.queryForObject("SELECT customer_id FROM customer_index WHERE case_instance_id = ? LIMIT 1", String.class, caseInstanceId);

        assertThat(priority).isEqualTo("LOW");
        assertThat(approvalStatus).isEqualTo("APPROVED");
        assertThat(customerId).isEqualTo("CUST99");
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
