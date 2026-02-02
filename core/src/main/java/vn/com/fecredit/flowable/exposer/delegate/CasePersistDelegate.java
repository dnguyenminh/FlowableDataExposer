package vn.com.fecredit.flowable.exposer.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.service.MetadataAnnotator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BPMN JavaDelegate used by the order process to persist a case snapshot into
 * the append-only {@code sys_case_data_store} table. This class is intentionally
 * defensive: it makes mutable copies of input variables, ensures minimal
 * type/@class metadata for downstream metadata resolution, and swallows
 * persistence/serialization exceptions so it does not break process execution.
 */
@Component("casePersistDelegate")
public class CasePersistDelegate implements JavaDelegate {

    @Autowired
    private ObjectMapper om;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MetadataAnnotator annotator;

    /**
     * Entry point invoked by Flowable. Kept short by delegating to helpers so
     * each helper stays well within the 20-line rule and is easier to unit-test.
     */
    @Override
    public void execute(DelegateExecution execution) {
        String caseInstanceId = execution.getProcessInstanceId();
        Map<String, Object> vars = copyVariables(execution);

        ensureClassAnnotations(vars);
        safeAnnotate(vars);

        // Diagnostic: visible in test output for deterministic E2E assertions
        System.out.println("CasePersistDelegate vars before persist: " + vars);

        String payload = stringify(vars);
        persistPayload(caseInstanceId, "Order", payload);
    }

    /** Create a mutable, shallow copy of the execution variables. */
    private Map<String, Object> copyVariables(DelegateExecution execution) {
        Map<String, Object> dst = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> src = execution.getVariables();
            if (src == null) return dst;
            for (Map.Entry<String, Object> e : src.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Map) dst.put(e.getKey(), new HashMap<>((Map<?, ?>) v));
                else if (v instanceof Iterable) {
                    List<Object> lst = new java.util.ArrayList<>();
                    for (Object it : (Iterable<?>) v) lst.add(it instanceof Map ? new HashMap<>((Map<?, ?>) it) : it);
                    dst.put(e.getKey(), lst);
                } else dst.put(e.getKey(), v);
            }
        } catch (Exception ignored) {
            // defensive: never fail process execution due to copy errors
        }
        return dst;
    }

    /**
     * Ensure minimal @class markers and common nested class defaults so
     * MetadataResolver / MetadataAnnotator can operate reliably.
     */
    private void ensureClassAnnotations(Map<String, Object> vars) {
        vars.putIfAbsent("@class", "Order");

        Object metaObj = vars.get("meta");
        if (!(metaObj instanceof Map)) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("@class", "Meta");
            meta.put("priority", "HIGH");
            vars.put("meta", meta);
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) metaObj;
            meta.putIfAbsent("@class", "Meta");
            meta.putIfAbsent("priority", "HIGH");
        }

        putClassIfMap(vars, "orderRules", "OrderRules");
        putClassIfMap(vars, "customer", "Customer");
        putClassIfMap(vars, "approvalDecision", "ApprovalDecision");
        putClassIfMap(vars, "params", "Params");

        Object itemsObj = vars.get("items");
        if (itemsObj instanceof Iterable) {
            for (Object it : (Iterable<?>) itemsObj) {
                if (it instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> im = (Map<String, Object>) it;
                    im.putIfAbsent("@class", "Item");
                }
            }
        }
    }

    private void putClassIfMap(Map<String, Object> vars, String key, String className) {
        Object o = vars.get(key);
        if (o instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            m.putIfAbsent("@class", className);
        }
    }

    /** Safely call the {@link MetadataAnnotator} if available. */
    private void safeAnnotate(Map<String, Object> vars) {
        if (annotator == null) return;
        try {
            annotator.annotate(vars, "Order");
        } catch (Exception ignored) {
            // best-effort: annotation must not interrupt the process
        }
    }

    /** Convert variables to a JSON payload, falling back to toString(). */
    private String stringify(Map<String, Object> vars) {
        try {
            return om.writeValueAsString(vars);
        } catch (Exception e) {
            return vars.toString();
        }
    }

    /** Persist the payload into the append-only case data table (best-effort). */
    private void persistPayload(String caseInstanceId, String entityType, String payload) {
        try {
            jdbc.update(
                    "INSERT INTO sys_case_data_store(case_instance_id, entity_type, payload, created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",
                    caseInstanceId, entityType, payload);
        } catch (Exception ignored) {
            // swallow - persistence is best-effort in the delegate
        }
    }
}

