package vn.com.fecredit.flowable.exposer.flowable;

import org.flowable.cmmn.api.delegate.DelegatePlanItemInstance;
import org.flowable.cmmn.api.delegate.PlanItemJavaDelegate;
import org.flowable.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import vn.com.fecredit.flowable.exposer.config.ApplicationContextProvider;

import java.util.Map;

/**
 * Plain PlanItemJavaDelegate that can be instantiated by the CMMN engine (no Spring injection required).
 * It starts the BPMN subprocess by key and passes through variables. Uses ApplicationContextProvider to obtain
 * the Spring RuntimeService so the delegate can run in the Spring Boot + Flowable environment.
 */
public class CasePersistCmmnPlanItemDelegate implements PlanItemJavaDelegate {

    private static final Logger LOGGER = LoggerFactory.getLogger(CasePersistCmmnPlanItemDelegate.class);

    @Override
    public void execute(DelegatePlanItemInstance planItem) {
        try {
            Map<String, Object> vars = planItem.getVariables();
            LOGGER.info("(plain delegate) invoked for planItemId={} caseInstanceId={}", planItem.getId(), planItem.getCaseInstanceId());

            ApplicationContext ctx = ApplicationContextProvider.getApplicationContext();
            if (ctx == null) {
                throw new IllegalStateException("Spring ApplicationContext is not available");
            }

            RuntimeService runtimeService = ctx.getBean(RuntimeService.class);
            var pi = runtimeService.startProcessInstanceByKey("orderProcess", vars);
            LOGGER.info("(plain delegate) started BPMN subprocess, id={}", pi == null ? "<null>" : pi.getId());
        } catch (Exception e) {
            LOGGER.error("Failed to start BPMN subprocess from CMMN (plain delegate)", e);
            throw new RuntimeException(e);
        }
    }
}
