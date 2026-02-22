package vn.com.fecredit.complexsample;

import com.jayway.jsonpath.JsonPath;
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
import vn.com.fecredit.flowable.exposer.job.CaseDataWorker;

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

    private static final String ORDER_ID = "12345";
    private static final String CUSTOMER_ID = "CUST01";
    private static final double TOTAL = 1234.56;

    @Test
    void startBpmnAndVerifyPlainTable() throws Exception {
        // ensureSchema() call removed - let CaseDataWorker create the table with all
        // columns

        Map<String, Object> req = Map.of("orderId", ORDER_ID, "customer", Map.of("id", CUSTOMER_ID), "total", TOTAL);
        String body = toJson(req);
        String caseInstanceId = startOrderProcess(body);

        // wait for blob persisted
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        Integer cnt = jdbc.queryForObject(
                                "SELECT count(*) FROM sys_case_data_store WHERE case_instance_id = ?", Integer.class,
                                caseInstanceId);
                        return cnt != null && cnt > 0;
                    } catch (Exception e) {
                        return false;
                    }
                });

        // Synchronous reindex to deterministically process this caseInstanceId in the
        // test
        CaseDataWorker worker = ctx.getBean(CaseDataWorker.class);
        worker.reindexByCaseInstanceId(caseInstanceId);

        // Immediately assert that the synchronous reindex processed the case
        // (deterministic check)
        Integer immediateCnt = jdbc.queryForObject("SELECT count(*) FROM case_plain_order WHERE case_instance_id = ?",
                Integer.class, caseInstanceId);
        assertThat(immediateCnt).isNotNull().isGreaterThan(0);

        // wait for plain table and assert row values (kept as fallback)
        verifyPlainTableForCase(caseInstanceId, TOTAL, CUSTOMER_ID);
    }

    @Test
    void startCmmnAndVerifyPlainTable() throws Exception {
        // ensureSchema() call removed - let CaseDataWorker create the table with all
        // columns
        Map<String, Object> req = Map.of("orderId", ORDER_ID, "customer", Map.of("id", CUSTOMER_ID), "total", TOTAL);
        String body = toJson(req);
        String caseInstanceId = startCmmnOrder(body);

        // wait for blob persisted
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        Integer cnt = jdbc.queryForObject(
                                "SELECT count(*) FROM sys_case_data_store WHERE case_instance_id = ?", Integer.class,
                                caseInstanceId);
                        return cnt != null && cnt > 0;
                    } catch (Exception e) {
                        return false;
                    }
                });

        // Synchronous reindex to deterministically process this caseInstanceId in the
        // test
        CaseDataWorker worker = ctx.getBean(CaseDataWorker.class);
        worker.reindexByCaseInstanceId(caseInstanceId);

        // Immediately assert that the synchronous reindex processed the case
        // (deterministic check)
        Integer immediateCnt = jdbc.queryForObject("SELECT count(*) FROM case_plain_order WHERE case_instance_id = ?",
                Integer.class, caseInstanceId);
        assertThat(immediateCnt).isNotNull().isGreaterThan(0);

        // wait for plain table and assert row values (kept as fallback)
        verifyPlainTableForCase(caseInstanceId, TOTAL, CUSTOMER_ID);
    }

    @Test
    void orderApiEndpointsAndStepsWork() throws Exception {
        // start process and force synchronous reindex as usual
        Map<String, Object> req = Map.of("orderId", ORDER_ID, "customer", Map.of("id", CUSTOMER_ID), "total", TOTAL);
        String body = toJson(req);
        String caseInstanceId = startOrderProcess(body);

        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        Integer cnt = jdbc.queryForObject(
                                "SELECT count(*) FROM sys_case_data_store WHERE case_instance_id = ?", Integer.class,
                                caseInstanceId);
                        return cnt != null && cnt > 0;
                    } catch (Exception e) {
                        return false;
                    }
                });
        CaseDataWorker worker = ctx.getBean(CaseDataWorker.class);
        worker.reindexByCaseInstanceId(caseInstanceId);
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250))
                .until(() -> {
                    try {
                        Integer cnt = jdbc.queryForObject(
                                "SELECT count(*) FROM case_plain_order WHERE case_instance_id = ?", Integer.class,
                                caseInstanceId);
                        return cnt != null && cnt > 0;
                    } catch (Exception e) {
                        return false;
                    }
                });

        // verify GET single
        ResponseEntity<Map> single = rest.getForEntity("http://localhost:" + port + "/api/orders/" + caseInstanceId, Map.class);
        assertThat(single.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(single.getBody()).containsEntry("orderTotal", TOTAL).containsEntry("customerId", CUSTOMER_ID);

        // verify list filtering
        ResponseEntity<java.util.List> listResp = rest.getForEntity("http://localhost:" + port + "/api/orders?customerId=" + CUSTOMER_ID, java.util.List.class);
        assertThat(listResp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(listResp.getBody()).isNotNull().isNotEmpty();

        // verify steps endpoint returns a list (possibly empty)
        ResponseEntity<java.util.List> steps = rest.getForEntity("http://localhost:" + port + "/api/orders/" + caseInstanceId + "/steps", java.util.List.class);
        assertThat(steps.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(steps.getBody()).isInstanceOf(java.util.List.class);
    }

    String startOrderProcess(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = rest.postForEntity("http://localhost:" + port + "/api/orders", request,
                Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> respBody = response.getBody();
        assertThat(respBody).isNotNull().containsKey("id");
        return String.valueOf(respBody.get("id"));
    }

    private String startCmmnOrder(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = rest.postForEntity("http://localhost:" + port + "/api/orders?type=cmmn", request,
                Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> respBody = response.getBody();
        assertThat(respBody).isNotNull().containsKey("id");
        return String.valueOf(respBody.get("id"));
    }

    private void verifyPlainTableForCase(String caseInstanceId, double total, String customerId) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    Integer cnt = jdbc.queryForObject(
                            "SELECT count(*) FROM case_plain_order WHERE case_instance_id = ?", Integer.class,
                            caseInstanceId);
                    assertThat(cnt).isNotNull().isGreaterThan(0);
                    String payload = jdbc.queryForObject(
                            "SELECT plain_payload FROM case_plain_order WHERE case_instance_id = ?", String.class,
                            caseInstanceId);
                    assertThat(payload).isNotNull();
                    Object totalVal = JsonPath.read(payload, "$.total");
                    assertThat(totalVal).isNotNull();
                    assertThat(((Number) totalVal).doubleValue()).isEqualTo(total);
                    Object custVal = JsonPath.read(payload, "$.customer.id");
                    assertThat(String.valueOf(custVal)).isEqualTo(customerId);
                });
    }

    private static String toJson(Map<String, Object> m) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
