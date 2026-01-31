package vn.com.fecredit.flowable.exposer.delegate;

import org.flowable.dmn.api.DmnDecisionService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component("dmnDecisionDelegate")
public class DmnDecisionDelegate implements JavaDelegate {
    private final DmnDecisionService dmnDecisionService; // may be null in constrained classpaths

    public DmnDecisionDelegate(ObjectProvider<DmnDecisionService> dmnDecisionServiceProvider) {
        this.dmnDecisionService = dmnDecisionServiceProvider.getIfAvailable();
    }

    @Override
    public void execute(DelegateExecution execution) {
        // Prefer the engine-backed DMN evaluation when available so runtime behavior
        // matches production DMN execution (also keeps model validation meaningful).
        if (dmnDecisionService != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> vars = execution.getVariables();

            List<Map<String, Object>> result = dmnDecisionService.createExecuteDecisionBuilder()
                .decisionKey("orderApproval")
                .variables(vars)
                .execute();

            if (result == null || result.isEmpty()) {
                execution.setVariable("approvalDecision", Collections.emptyMap());
            } else if (result.size() == 1) {
                execution.setVariable("approvalDecision", result.get(0));
            } else {
                execution.setVariable("approvalDecision", result);
            }
            return;
        }

        // Defensive fallback: reproduce DMN rules in Java so tests can still run
        // on constrained classpaths (this branch should be rare now that the DMN
        // starter is on the classpath).
        Object totalObj = execution.getVariable("total");
        double total = 0.0;
        if (totalObj instanceof Number) total = ((Number) totalObj).doubleValue();

        Map<String, Object> decision = new HashMap<>();
        if (total <= 1000) {
            decision.put("status", "APPROVE");
            decision.put("reason", "auto-approve-by-rule");
            decision.put("priority", "LOW");
        } else if (total > 1000 && total <= 10000) {
            decision.put("status", "REVIEW");
            decision.put("reason", "needs-business-review");
            decision.put("priority", "MEDIUM");
        } else {
            decision.put("status", "HOLD");
            decision.put("reason", "escalate-to-senior");
            decision.put("priority", "HIGH");
        }
        execution.setVariable("approvalDecision", decision);
    }
}
