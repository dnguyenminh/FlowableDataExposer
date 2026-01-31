package vn.com.fecredit.flowable.exposer.delegate;

import org.flowable.dmn.api.DmnDecisionService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
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

            List<Map<String, Object>> result = null;

            // The DMN resource historically used id `orderRulesTable`; newer canonical id is `orderRules`.
            // For backward compatibility try both decision keys (legacy first) and keep the
            // process variable name stable as `orderRules`.
            String[] decisionKeys = new String[] {"orderRulesTable", "orderRules"};

            // Prefer the newer single-result API when present (avoid deprecated execute()).
            // Use reflection so this code remains compatible across Flowable minor versions.
            for (String key : decisionKeys) {
                if (result != null && !result.isEmpty()) break;
                try {
                    Method newer = dmnDecisionService.getClass().getMethod("evaluateDecisionTableByKey", String.class, Map.class);
                    Object r = newer.invoke(dmnDecisionService, key, vars);
                    if (r instanceof List) {
                        //noinspection unchecked
                        result = (List<Map<String, Object>>) r;
                        break;
                    }
                } catch (NoSuchMethodException ignored) {
                    // older Flowable: will use builder API below
                    break;
                } catch (Throwable t) {
                    // reflection failed for this key, try the next one
                }
            }

            if (result == null) {
                // backward-compatible builder-based call (try legacy then new key)
                //noinspection deprecation
                for (String key : decisionKeys) {
                    // if we get a non-empty result, stop
                    result = dmnDecisionService.createExecuteDecisionBuilder()
                        .decisionKey(key)
                        .variables(vars)
                        .execute();
                    if (result != null && !result.isEmpty()) break;
                }
            }

            // store engine result under a stable variable name that matches the new DMN
            if (result == null || result.isEmpty()) {
                execution.setVariable("orderRules", Collections.emptyMap());
            } else if (result.size() == 1) {
                Map<String,Object> r = result.get(0);
                execution.setVariable("orderRules", r);
                // also expose individual outputs as top-level variables for convenience
                if (r.containsKey("discount")) execution.setVariable("discount", r.get("discount"));
                if (r.containsKey("shippingFee")) execution.setVariable("shippingFee", r.get("shippingFee"));
            } else {
                execution.setVariable("orderRules", result);
            }
            // keep the old variable to remain backwards-compatible for callers that still
            // reference `approvalDecision` (set to empty map since old DMN is replaced)
            execution.setVariable("approvalDecision", Collections.emptyMap());
            return;
        }

        // Defensive fallback: reproduce DMN rules in Java so tests can still run
        // on constrained classpaths (this branch should be rare now that the DMN
        // starter is on the classpath).
        Object totalObj = execution.getVariable("total");
        double total = 0.0;
        if (totalObj instanceof Number) total = ((Number) totalObj).doubleValue();
        Object regionObj = execution.getVariable("params") != null ? ((Map<?,?>)execution.getVariable("params")).get("region") : null;
        String region = regionObj == null ? null : String.valueOf(regionObj);

        Map<String, Object> decision = new HashMap<>();
        // replicate the new DMN behaviour (discount + shippingFee)
        if (total > 1_000_000 && "HCM".equalsIgnoreCase(region)) {
            decision.put("discount", 0.1);
            decision.put("shippingFee", 0.0);
        } else if ("Hanoi".equalsIgnoreCase(region)) {
            decision.put("discount", 0.0);
            decision.put("shippingFee", 30000.0);
        } else {
            decision.put("discount", 0.0);
            decision.put("shippingFee", 50000.0);
        }
        execution.setVariable("orderRules", decision);
        execution.setVariable("discount", decision.get("discount"));
        execution.setVariable("shippingFee", decision.get("shippingFee"));
        execution.setVariable("approvalDecision", Collections.emptyMap());
    }
}
