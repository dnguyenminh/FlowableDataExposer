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

/**
 * DMN evaluation delegate used by the order process.
 *
 * <p>This delegate prefers the engine's DMN APIs when available and falls
 * back to a deterministic Java implementation when the DMN engine is not
 * present on the classpath (useful for lightweight test profiles).</p>
 */
@Component("dmnDecisionDelegate")
public class DmnDecisionDelegate implements JavaDelegate {
    private final DmnDecisionService dmnDecisionService; // may be null in constrained classpaths

    public DmnDecisionDelegate(ObjectProvider<DmnDecisionService> dmnDecisionServiceProvider) {
        this.dmnDecisionService = dmnDecisionServiceProvider.getIfAvailable();
    }

    /**
     * Entry point invoked by Flowable. Keep this method short by delegating
     * to helpers (engine path, application of results, and Java fallback).
     */
    @Override
    public void execute(DelegateExecution execution) {
        if (dmnDecisionService != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> vars = execution.getVariables();
            List<Map<String, Object>> result = evaluateWithEngine(vars);
            applyDecisionResult(execution, result);
            return;
        }

        // Engine not available on the classpath — use a deterministic Java fallback.
        javaFallback(execution);
    }

    /** Try engine-backed evaluation: reflection-first (new API) then builder-based legacy API. */
    private List<Map<String, Object>> evaluateWithEngine(Map<String, Object> vars) {
        String[] keys = new String[] {"orderRulesTable", "orderRules"};
        List<Map<String, Object>> result = null;

        for (String key : keys) {
            result = tryReflectionEvaluate(key, vars);
            if (result != null && !result.isEmpty()) return result;
        }

        for (String key : keys) {
            result = tryBuilderEvaluate(key, vars);
            if (result != null && !result.isEmpty()) return result;
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tryReflectionEvaluate(String key, Map<String, Object> vars) {
        try {
            Method newer = dmnDecisionService.getClass().getMethod("evaluateDecisionTableByKey", String.class, Map.class);
            Object r = newer.invoke(dmnDecisionService, key, vars);
            if (r instanceof List) return (List<Map<String, Object>>) r;
        } catch (NoSuchMethodException ignored) {
            // older Flowable versions — builder API will be used
        } catch (Throwable ignored) {
            // reflection attempted but failed for this key
        }
        return null;
    }

    private List<Map<String, Object>> tryBuilderEvaluate(String key, Map<String, Object> vars) {
        try {
            //noinspection deprecation
            return dmnDecisionService.createExecuteDecisionBuilder()
                .decisionKey(key)
                .variables(vars)
                .execute();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Apply engine/java decision result to the execution (keeps backward compatibility). */
    private void applyDecisionResult(DelegateExecution execution, List<Map<String, Object>> result) {
        if (result == null || result.isEmpty()) {
            execution.setVariable("orderRules", Collections.emptyMap());
            execution.setVariable("approvalDecision", Collections.emptyMap());
            return;
        }

        if (result.size() == 1) {
            Map<String, Object> r = result.get(0);
            execution.setVariable("orderRules", r);
            if (r.containsKey("discount")) execution.setVariable("discount", r.get("discount"));
            if (r.containsKey("shippingFee")) execution.setVariable("shippingFee", r.get("shippingFee"));
            execution.setVariable("approvalDecision", Collections.emptyMap());
            return;
        }

        execution.setVariable("orderRules", result);
        execution.setVariable("approvalDecision", Collections.emptyMap());
    }

    /** Simple, deterministic Java implementation used when DMN engine is absent. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void javaFallback(DelegateExecution execution) {
        Object totalObj = execution.getVariable("total");
        double total = 0.0;
        if (totalObj instanceof Number) total = ((Number) totalObj).doubleValue();
        Object regionObj = execution.getVariable("params") != null ? ((Map)execution.getVariable("params")).get("region") : null;
        String region = regionObj == null ? null : String.valueOf(regionObj);

        Map<String, Object> decision = new HashMap<>();
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
