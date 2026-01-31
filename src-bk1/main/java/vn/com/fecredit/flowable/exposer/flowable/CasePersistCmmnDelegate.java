package vn.com.fecredit.flowable.exposer.flowable;

import org.flowable.cmmn.api.delegate.DelegatePlanItemInstance;
import org.flowable.cmmn.api.delegate.PlanItemJavaDelegate;
import org.flowable.engine.RuntimeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Component("casePersistCmmnDelegate")
public class CasePersistCmmnDelegate implements PlanItemJavaDelegate {

    private static final Logger LOGGER = LoggerFactory.getLogger(CasePersistCmmnDelegate.class);

    @Autowired
    private RuntimeService runtimeService;

    @Override
    public void execute(DelegatePlanItemInstance planItem) {
        try {
            Map<String, Object> vars = planItem.getVariables();
            LOGGER.debug("CMMN delegate invoked for planItemId={} caseInstanceId={}; vars={}", planItem.getId(), planItem.getCaseInstanceId(), vars);

            // Start the BPMN subprocess and pass through variables — BPMN will persist/index
            var pi = runtimeService.startProcessInstanceByKey("orderProcess", vars);
            if (pi != null) {
                LOGGER.debug("Started BPMN subprocess from CMMN — processInstanceId={}", pi.getId());
            } else {
                LOGGER.warn("runtimeService.startProcessInstanceByKey returned null for key=orderProcess");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to start BPMN subprocess from CMMN", e);
            throw new RuntimeException("Failed to start BPMN subprocess from CMMN", e);
        }
    }
}