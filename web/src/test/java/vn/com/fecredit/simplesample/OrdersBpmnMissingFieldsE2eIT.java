package vn.com.fecredit.simplesample;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class OrdersBpmnMissingFieldsE2eIT {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void testBpmnOrderMissingOptionalFields() {
        // start process with payload missing customer and priority
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"orderId\":\"12345\", \"total\":1234.56}"; // no customer
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = rest.postForEntity("http://localhost:" + port + "/api/orders", request, Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = response.getBody();
        assertThat(resp).isNotNull().containsKey("id");
        String caseInstanceId = String.valueOf(resp.get("id"));

        // Wait for blob
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM sys_case_data_store WHERE case_instance_id = ?", Integer.class, caseInstanceId);
                        return cnt != null && cnt > 0;
                    } catch (Exception e) { return false; }
                });

        // Trigger reindex (deterministic fallback)
        ResponseEntity<Void> r = rest.postForEntity("http://localhost:" + port + "/api/orders/" + caseInstanceId + "/reindex", null, Void.class);
        assertThat(r.getStatusCode().is2xxSuccessful() || r.getStatusCode().value() == 202).isTrue();

        // Wait for plain order to be available via API
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    ResponseEntity<Map> plain = rest.getForEntity("http://localhost:" + port + "/api/orders/" + caseInstanceId, Map.class);
                    assertThat(plain.getStatusCode().is2xxSuccessful()).isTrue();
                    Map<String, Object> bodyMap = plain.getBody();
                    assertThat(bodyMap).containsEntry("orderTotal", 1234.56);
                    // customer may be absent; ensure API still responds
                });
    }
}
