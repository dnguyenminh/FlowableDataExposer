package vn.com.fecredit.flowable.exposer.flowable;

import org.flowable.cmmn.api.delegate.DelegatePlanItemInstance;
import org.flowable.cmmn.api.delegate.PlanItemJavaDelegate;
import org.flowable.engine.RuntimeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.service.CaseDataWorker;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component("caseInvokeCmmnDelegate")
public class CaseInvokeCmmnDelegate implements PlanItemJavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(CaseInvokeCmmnDelegate.class);

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private CaseDataWorker caseDataWorker; // not used here but kept if future in-process indexing required

    @Override
    public void execute(DelegatePlanItemInstance planItem) {
        try {
            Map<String, Object> vars = planItem.getVariables();
            log.info("CaseInvokeCmmnDelegate.execute invoked for planItem(id={}, name={}) varsCount={}", planItem.getId(), planItem.getName(), vars == null ? 0 : vars.size());
            // start the BPMN subprocess and pass through all variables
            var pi = runtimeService.startProcessInstanceByKey("orderProcess", vars);
            if (pi != null) {
                log.info("Started BPMN subprocess from CMMN: processInstanceId={} processDefinitionKey={}", pi.getId(), pi.getProcessDefinitionKey());
            } else {
                log.warn("runtimeService.startProcessInstanceByKey returned null for key=orderProcess");
            }
        } catch (Exception e) {
            log.error("Failed to start BPMN subprocess from CMMN - rethrowing", e);
            throw new RuntimeException("Failed to start BPMN subprocess from CMMN", e);
        }
    }
}
