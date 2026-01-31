package vn.com.fecredit.flowable.exposer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import vn.com.fecredit.flowable.exposer.entity.SysCaseDataStore;
import vn.com.fecredit.flowable.exposer.repository.CasePlainOrderRepository;
import vn.com.fecredit.flowable.exposer.repository.IdxReportRepository;
import vn.com.fecredit.flowable.exposer.repository.SysCaseDataStoreRepository;
import vn.com.fecredit.flowable.exposer.service.CaseDataWorker;
import vn.com.fecredit.flowable.exposer.service.ExposeInterceptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class CaseDataWorkerTest {

    @Autowired
    ExposeInterceptor interceptor;

    @Autowired
    org.flowable.engine.RuntimeService runtimeService;

    @Autowired
    org.flowable.engine.HistoryService historyService;

    @Autowired
    org.flowable.engine.RepositoryService repositoryService;

    @Autowired
    org.flowable.cmmn.api.CmmnRuntimeService cmmnRuntimeService;

    @Autowired
    SysCaseDataStoreRepository storeRepo;

    @Autowired
    CaseDataWorker worker;

    @Autowired
    IdxReportRepository idxRepo;

    @Autowired
    CasePlainOrderRepository plainRepo;

    @Test
    @org.springframework.transaction.annotation.Transactional
    public void bpmnTest_fullRoundtrip_encrypt_store_and_index_and_reindex() throws Exception {
        Map<String, Object> item = new HashMap<>();
        item.put("id", "item-123");
        List<Map<String,Object>> items = new ArrayList<>();
        items.add(item);

        Map<String,Object> params = new HashMap<>();
        params.put("color", "red");

        Map<String,Object> variables = new HashMap<>();
        variables.put("total", 123.45);
        variables.put("items", items);
        variables.put("params", params);
        variables.put("initiator", "test-user");

        // Start the Flowable BPMN process which will trigger CasePersistDelegate
        org.flowable.engine.runtime.ProcessInstance pi = runtimeService.startProcessInstanceByKey("orderProcess", variables);
        assertNotNull(pi);
        assertNotNull(pi.getId());

        // The CasePersistDelegate should have persisted the case and indexed it synchronously
        var saved = storeRepo.findByCaseInstanceId(pi.getId());
        assertNotNull(saved);
        assertEquals("Order", saved.getEntityType());

        // Verify index exists (created by CasePersistDelegate -> CaseDataWorker.process)
        var idx = idxRepo.findByCaseInstanceId(pi.getId());
        assertTrue(idx.isPresent());
        assertEquals(123.45, idx.get().getTotalAmount(), 0.01);
        assertEquals("item-123", idx.get().getItem1Id());
        assertEquals("red", idx.get().getColorAttr());

        // assert plain-table was written
        var maybePlain = plainRepo.findByCaseInstanceId(pi.getId());
        assertTrue(maybePlain.isPresent(), "Expected a row in case_plain_order");
        assertEquals(123.45, maybePlain.get().getOrderTotal(), 0.001);

        // Test reindexAll: delete index and rebuild
        idxRepo.deleteByCaseInstanceId(pi.getId());
        assertTrue(idxRepo.findByCaseInstanceId(pi.getId()).isEmpty());

        worker.reindexAll("Order");
        var idx2 = idxRepo.findByCaseInstanceId(pi.getId());
        assertTrue(idx2.isPresent());
        assertEquals(123.45, idx2.get().getTotalAmount(), 0.01);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    public void cmmnTest_cmmn_invokes_bpmn_subprocess() throws Exception {
        Map<String, Object> item = new HashMap<>();
        item.put("id", "item-456");
        List<Map<String,Object>> items = new ArrayList<>();
        items.add(item);

        Map<String,Object> params = new HashMap<>();
        params.put("color", "blue");

        Map<String,Object> variables = new HashMap<>();
        variables.put("total", 456.78);
        variables.put("items", items);
        variables.put("params", params);
        variables.put("initiator", "test-user");

        // Sanity: ensure BPMN process definition is deployed and available before starting the case
        assertTrue(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("orderProcess")
                .count() > 0, "Expected BPMN processDefinition 'orderProcess' to be deployed");

        // Start the Flowable CMMN case which will invoke the BPMN orderProcess as a subprocess
        org.flowable.cmmn.api.runtime.CaseInstance ci = cmmnRuntimeService.createCaseInstanceBuilder()
                .caseDefinitionKey("orderCase")
                .variables(variables)
                .start();
        assertNotNull(ci);
        assertNotNull(ci.getId());

        // Debug: Check case state
        System.out.println("Case Instance ID: " + ci.getId());
        System.out.println("Case State: " + ci.getState());

        // Check plan items
        var planItems = cmmnRuntimeService.createPlanItemInstanceQuery()
                .caseInstanceId(ci.getId())
                .list();
        System.out.println("Plan Items count: " + planItems.size());
        for (var planItem : planItems) {
            System.out.println("  Plan Item: " + planItem.getName() + " - State: " + planItem.getState());
        }

        // Poll briefly for the BPMN subprocess to appear (guards against small timing differences)
        List<org.flowable.engine.runtime.ProcessInstance> processInstances = Collections.emptyList();
        final int maxAttempts = 40; // ~2s (40 * 50ms)
        int attempt = 0;
        while (attempt++ < maxAttempts) {
            processInstances = runtimeService.createProcessInstanceQuery()
                    .processDefinitionKey("orderProcess")
                    .list();
            if (!processInstances.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }

        // If the process completed very quickly it may not be returned by the runtime query —
        // fall back to the historic instance query to make the test deterministic.
        String processInstanceId = null;
        if (!processInstances.isEmpty()) {
            processInstanceId = processInstances.get(0).getId();
        } else {
            var historic = historyService.createHistoricProcessInstanceQuery()
                    .processDefinitionKey("orderProcess")
                    .orderByProcessInstanceStartTime()
                    .desc()
                    .list();
            if (!historic.isEmpty()) {
                processInstanceId = historic.get(0).getId();
            }
        }

        assertNotNull(processInstanceId, "CMMN should have started a BPMN subprocess (runtime or historic)");
        System.out.println("Resolved processInstanceId: " + processInstanceId);

        // Use the resolved processInstanceId for subsequent persistence/index assertions
        var saved = storeRepo.findByCaseInstanceId(processInstanceId);
        assertNotNull(saved, "BPMN subprocess should have persisted case data");
        assertEquals("Order", saved.getEntityType());

        // Verify index exists (created by BPMN subprocess: CasePersistDelegate -> CaseDataWorker.process)
        var idx = idxRepo.findByCaseInstanceId(processInstanceId);
        assertTrue(idx.isPresent(), "Index should exist from BPMN subprocess");
        assertEquals(456.78, idx.get().getTotalAmount(), 0.01);
        assertEquals("item-456", idx.get().getItem1Id());
        assertEquals("blue", idx.get().getColorAttr());

        // Test reindexAll: delete index and rebuild
        idxRepo.deleteByCaseInstanceId(processInstanceId);
        assertTrue(idxRepo.findByCaseInstanceId(processInstanceId).isEmpty());

        worker.reindexAll("Order");
        var idx2 = idxRepo.findByCaseInstanceId(processInstanceId);
        assertTrue(idx2.isPresent());
        assertEquals(456.78, idx2.get().getTotalAmount(), 0.01);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    public void combinedDeployment_contains_bpmn_and_cmmn_and_cmmn_starts_bpmn() throws Exception {
        // The CombinedDeployment component should have created a single deployment containing both resources
        var dep = repositoryService.createDeploymentQuery()
                .deploymentName("order-combined-deployment")
                .singleResult();
        assertNotNull(dep, "Expected combined deployment to exist");

        var resourceNames = repositoryService.getDeploymentResourceNames(dep.getId());
        assertTrue(resourceNames.contains("processes/orderProcess.bpmn20.xml"));
        assertTrue(resourceNames.contains("cases/orderCase.cmmn"));

        // Start the case and assert it starts the BPMN subprocess (runtime OR historic fallback)
        Map<String, Object> item = new HashMap<>();
        item.put("id", "item-999");
        List<Map<String,Object>> items = new ArrayList<>();
        items.add(item);

        Map<String,Object> params = new HashMap<>();
        params.put("color", "green");

        Map<String,Object> variables = new HashMap<>();
        variables.put("total", 9.99);
        variables.put("items", items);
        variables.put("params", params);
        variables.put("initiator", "test-user");

        org.flowable.cmmn.api.runtime.CaseInstance ci = cmmnRuntimeService.createCaseInstanceBuilder()
                .caseDefinitionKey("orderCase")
                .variables(variables)
                .start();
        assertNotNull(ci);

        // wait for runtime instance or historic
        String processInstanceId = null;
        for (int i = 0; i < 40; i++) {
            var pis = runtimeService.createProcessInstanceQuery().processDefinitionKey("orderProcess").list();
            if (!pis.isEmpty()) {
                processInstanceId = pis.get(0).getId();
                break;
            }
            Thread.sleep(50);
        }
        if (processInstanceId == null) {
            var historic = historyService.createHistoricProcessInstanceQuery()
                    .processDefinitionKey("orderProcess")
                    .orderByProcessInstanceStartTime()
                    .desc()
                    .list();
            if (!historic.isEmpty()) {
                processInstanceId = historic.get(0).getId();
            }
        }
        assertNotNull(processInstanceId, "CMMN should have started a BPMN subprocess");

        var saved = storeRepo.findByCaseInstanceId(processInstanceId);
        assertNotNull(saved);
        assertEquals("Order", saved.getEntityType());

        var idx = idxRepo.findByCaseInstanceId(processInstanceId);
        assertTrue(idx.isPresent());
        assertEquals(9.99, idx.get().getTotalAmount(), 0.01);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    public void cmmnToBpmn_variableMapping_passesVariables() throws Exception {
        Map<String,Object> variables = new HashMap<>();
        variables.put("total", 314.15);
        variables.put("initiator", "test-user");

        // start the case which (per model) should start the BPMN subprocess
        org.flowable.cmmn.api.runtime.CaseInstance ci = cmmnRuntimeService.createCaseInstanceBuilder()
                .caseDefinitionKey("orderCase")
                .variables(variables)
                .start();
        assertNotNull(ci);

        // wait briefly for the BPMN subprocess to appear (runtime or historic fallback)
        String processInstanceId = null;
        for (int i = 0; i < 40; i++) {
            var pis = runtimeService.createProcessInstanceQuery().processDefinitionKey("orderProcess").list();
            if (!pis.isEmpty()) {
                processInstanceId = pis.get(0).getId();
                break;
            }
            Thread.sleep(50);
        }
        if (processInstanceId == null) {
            var historic = historyService.createHistoricProcessInstanceQuery()
                    .processDefinitionKey("orderProcess")
                    .orderByProcessInstanceStartTime()
                    .desc()
                    .list();
            if (!historic.isEmpty()) {
                processInstanceId = historic.get(0).getId();
            }
        }
        assertNotNull(processInstanceId, "Expected a BPMN subprocess to be started by the case");

        // Prefer runtime variable (process still running); fall back to historic variable if completed
        Object totalValue = runtimeService.getVariable(processInstanceId, "total");
        if (totalValue == null) {
            var hv = historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .variableName("total")
                    .singleResult();
            assertNotNull(hv, "Historic variable 'total' should be present");
            totalValue = hv.getValue();
        }

        assertTrue(totalValue instanceof Number, "'total' should be a numeric process variable");
        assertEquals(314.15, ((Number) totalValue).doubleValue(), 0.0001);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    public void bpmn_with_dmn_populates_plain_columns() throws Exception {
        Map<String,Object> customer = new HashMap<>();
        customer.put("id", "cust-007");
        customer.put("name", "Acme Ltd");

        Map<String, Object> variables = new HashMap<>();
        variables.put("total", 2500); // should trigger REVIEW rule
        variables.put("customer", customer);
        variables.put("initiator", "dmn-tester");

        org.flowable.engine.runtime.ProcessInstance pi = runtimeService.startProcessInstanceByKey("orderProcess", variables);
        assertNotNull(pi);

        // wait briefly for worker to persist plain row
        var maybePlain = plainRepo.findByCaseInstanceId(pi.getId());
        int attempts = 0;
        while (attempts++ < 20 && maybePlain.isEmpty()) {
            Thread.sleep(100);
            maybePlain = plainRepo.findByCaseInstanceId(pi.getId());
        }
        assertTrue(maybePlain.isPresent(), "Expected a plain row for DMN-augmented process");
        var plain = maybePlain.get();
        assertEquals("cust-007", plain.getCustomerId());
        assertEquals("Acme Ltd", plain.getCustomerName());
        assertEquals("MEDIUM", plain.getOrderPriority());
        assertEquals("REVIEW", plain.getApprovalStatus());
        assertEquals("needs-business-review", plain.getDecisionReason());
    }
}
