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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class OrdersCmmnE2eIT {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void testCmmnOrderFlow_and_stepsEndpoint() {
        // start CMMN case with payload provided by user
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"total\":45689,\"customer\":{\"id\":\"d786\",\"name\":\"Abcfhfh\"},\"meta\":{\"priority\":\"LOW\"},\"notes\":\"\"}";
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = rest.postForEntity("http://localhost:" + port + "/api/orders?type=cmmn", request, Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = response.getBody();
        assertThat(resp).isNotNull().containsKey("id");
        String caseInstanceId = String.valueOf(resp.get("id"));

        // Debug: print DB rows for this caseInstanceId immediately after creation
        try {
            System.out.println("DEBUG: checking DB for caseInstanceId=" + caseInstanceId);
            Integer cnt0 = jdbc.queryForObject("SELECT count(*) FROM sys_case_data_store WHERE case_instance_id = ?", Integer.class, caseInstanceId);
            System.out.println("DEBUG: initial sys_case_data_store count for case=" + cnt0);
            Integer reqCnt0 = jdbc.queryForObject("SELECT count(*) FROM sys_expose_requests WHERE case_instance_id = ?", Integer.class, caseInstanceId);
            System.out.println("DEBUG: initial sys_expose_requests count for case=" + reqCnt0);
        } catch (Throwable t) {
            t.printStackTrace();
        }

        // Wait for the persisted blob to appear in sys_case_data_store
        // Increased timeout to reduce flakiness when running in CI
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM sys_case_data_store WHERE case_instance_id = ?", Integer.class, caseInstanceId);
                        return cnt != null && cnt > 0;
                    } catch (Exception e) {
                        return false;
                    }
                });

        // Hit steps endpoint and ensure we get a JSON array (may be empty if no tasks yet)
        ResponseEntity<List> stepsResp = rest.getForEntity("http://localhost:" + port + "/api/orders/" + caseInstanceId + "/steps", List.class);
        assertThat(stepsResp.getStatusCode().is2xxSuccessful()).isTrue();
        List<?> steps = stepsResp.getBody();
        assertThat(steps).isNotNull();

        // Trigger reindex to ensure worker can process
        ResponseEntity<Void> r = rest.postForEntity("http://localhost:" + port + "/api/orders/" + caseInstanceId + "/reindex", null, Void.class);
        assertThat(r.getStatusCode().is2xxSuccessful() || r.getStatusCode().value() == 202).isTrue();

        // Wait for plain order to be available via public API (allow extra time for CMMN async flows)
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollDelay(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    ResponseEntity<Map> plain = rest.getForEntity("http://localhost:" + port + "/api/orders/" + caseInstanceId, Map.class);
                    assertThat(plain.getStatusCode().is2xxSuccessful()).isTrue();
                    Map<String, Object> bodyMap = plain.getBody();
                    assertThat(bodyMap).containsEntry("orderTotal", 45689.0);
                });
    }
}
