package vn.com.fecredit.flowable.exposer;

import org.flowable.cmmn.api.CmmnRepositoryService;
import org.flowable.cmmn.api.CmmnRuntimeService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.HistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class CombinedDeploymentIntegrationTest {

    @MockBean
    vn.com.fecredit.flowable.exposer.config.CombinedDeployment combinedDeployment; // prevent @PostConstruct side-effects for this focused test

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private CmmnRepositoryService cmmnRepositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private CmmnRuntimeService cmmnRuntimeService;

    @Autowired
    private HistoryService historyService;

    @AfterEach
    public void cleanup() {
        repositoryService.createDeploymentQuery().deploymentName("reproducer-deployment").list()
                .forEach(d -> repositoryService.deleteDeployment(d.getId(), true));
        cmmnRepositoryService.createDeploymentQuery().deploymentName("reproducer-deployment").list()
                .forEach(d -> cmmnRepositoryService.deleteDeployment(d.getId(), true));
    }

    @Test
    public void deployBpmnAndCmmnTogether_caseStartsBpmn() throws Exception {
        // deploy both resources in a single deployment (same-deployment scenario)
        var dep = repositoryService.createDeployment()
                .name("reproducer-deployment")
                .addClasspathResource("processes/orderProcess.bpmn20.xml")
                .addClasspathResource("cases/orderCase.cmmn")
                .deploy();

        assertTrue(repositoryService.createProcessDefinitionQuery().processDefinitionKey("orderProcess").count() > 0);
        assertTrue(cmmnRepositoryService.createCaseDefinitionQuery().caseDefinitionKey("orderCase").count() > 0);

        // start the case and assert it starts the BPMN subprocess (runtime OR historic fallback)
        var ci = cmmnRuntimeService.createCaseInstanceBuilder()
                .caseDefinitionKey("orderCase")
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
        assertNotNull(processInstanceId, "CMMN should have started a BPMN subprocess when deployed together");
    }
}
