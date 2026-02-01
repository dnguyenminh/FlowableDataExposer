package vn.com.fecredit.flowable.exposer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.fecredit.flowable.exposer.entity.CasePlainOrder;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(vn.com.fecredit.flowable.exposer.web.OrderController.class)
@AutoConfigureMockMvc
public class OrderControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @org.springframework.boot.test.mock.mockito.MockBean
    RuntimeService runtimeService;

    @org.springframework.boot.test.mock.mockito.MockBean
    vn.com.fecredit.flowable.exposer.repository.CasePlainOrderRepository casePlainOrderRepository;

    @org.springframework.beans.factory.annotation.Autowired
    StubWorker caseDataWorker;

    // Provide lightweight test beans to exercise the controller's reflective paths
    @org.springframework.boot.test.context.TestConfiguration
    static class Cfg {
        @org.springframework.context.annotation.Bean(name = "cmmnRuntimeService")
        public CmmnRuntimeStub cmmnRuntimeService() { return new CmmnRuntimeStub(); }

        @org.springframework.context.annotation.Bean(name = "caseDataWorker")
        public StubWorker caseDataWorker() { return org.mockito.Mockito.spy(new StubWorker()); }
    }

    public static class StubWorker { public void reindexAll(String s) { /* spy target */ } }

    // public stub types so reflective access from controller (different package) succeeds under the module/classloader rules
    public static class CmmnRuntimeStub {
        public Builder createCaseInstanceBuilder() { return new Builder(); }
        public static class Builder {
            public Builder caseDefinitionKey(String k) { return this; }
            public Builder variables(java.util.Map m) { return this; }
            public CaseInstanceStub start() { return new CaseInstanceStub(); }
        }
        public static class CaseInstanceStub { public String getId() { return "case-1"; } }
    }

    @Test
    void startOrder_bpmn_and_cmmn_and_query_plain_work() throws Exception {
        ProcessInstance pi = mock(ProcessInstance.class);
        when(pi.getId()).thenReturn("proc-1");
        when(runtimeService.startProcessInstanceByKey(eq("orderProcess"), anyMap())).thenReturn(pi);

        String payload = "{ \"total\": 12.5, \"customerId\": \"C123\" }";
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("proc-1"));

        // CMMN start exercised via TestConfiguration stub (returns case-1)
        mvc.perform(post("/api/orders?type=cmmn").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("case-1"));

        // query plain
        CasePlainOrder p = new CasePlainOrder(); p.setCaseInstanceId("case-1"); p.setOrderTotal(123.45); p.setOrderPriority("HIGH"); p.setApprovalStatus("PENDING");
        when(casePlainOrderRepository.findByCaseInstanceId("case-1")).thenReturn(java.util.Optional.of(p));

        mvc.perform(get("/api/orders/case-1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderTotal").value(123.45))
                .andExpect(jsonPath("$.orderPriority").value("HIGH"));
    }

    @Test
    void reindex_endpoint_falls_back_to_reindexAll_when_perCase_missing() throws Exception {
        mvc.perform(post("/api/orders/case-42/reindex")).andExpect(status().isAccepted());
        verify(caseDataWorker, times(1)).reindexAll("Order");
    }
}
