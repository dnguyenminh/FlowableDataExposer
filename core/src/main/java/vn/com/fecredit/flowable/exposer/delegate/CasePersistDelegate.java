package vn.com.fecredit.flowable.exposer.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.service.MetadataAnnotator;

import java.util.HashMap;
import java.util.Map;

@Component("casePersistDelegate")
public class CasePersistDelegate implements JavaDelegate {

    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MetadataAnnotator annotator;

    @Override
    public void execute(DelegateExecution execution) {
        String caseInstanceId = execution.getProcessInstanceId();
        Map<String, Object> vars = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> existing = execution.getVariables();
            if (existing != null) {
                for (Map.Entry<String, Object> e : existing.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Map) {
                        // create mutable copy
                        @SuppressWarnings("unchecked")
                        Map<String, Object> mv = new HashMap<>((Map<String, Object>) v);
                        vars.put(e.getKey(), mv);
                    } else if (v instanceof Iterable) {
                        // copy list/iterable and make any Map items mutable
                        java.util.List<Object> lst = new java.util.ArrayList<>();
                        for (Object it : (Iterable<?>) v) {
                            if (it instanceof Map) lst.add(new HashMap<>((Map) it));
                            else lst.add(it);
                        }
                        vars.put(e.getKey(), lst);
                    } else {
                        vars.put(e.getKey(), v);
                    }
                }
            }
        } catch (Exception ignored) {}

        // Ensure root class and common nested classes so metadata resolution and annotator work
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

        Object orderRules = vars.get("orderRules");
        if (orderRules instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) orderRules;
            r.putIfAbsent("@class", "OrderRules");
        }

        Object customer = vars.get("customer");
        if (customer instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) customer;
            c.putIfAbsent("@class", "Customer");
        }

        Object approval = vars.get("approvalDecision");
        if (approval instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) approval;
            a.putIfAbsent("@class", "ApprovalDecision");
        }

        Object params = vars.get("params");
        if (params instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) params;
            p.putIfAbsent("@class", "Params");
        }

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

        try {
            // let annotator add any missing @class according to metadata
            if (annotator != null) {
                try { annotator.annotate(vars, "Order"); } catch (Exception ignored) {}
            }
        } catch (Throwable ignored) {}

        // Diagnostic: print vars so E2E tests can verify what the delegate sees at runtime
        System.out.println("CasePersistDelegate vars before persist: " + vars);

        String payload;
        try {
            payload = om.writeValueAsString(vars);
        } catch (Exception e) {
            payload = vars.toString();
        }

        try {
            jdbc.update("INSERT INTO sys_case_data_store(case_instance_id, entity_type, payload, created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",
                    caseInstanceId, "Order", payload);
        } catch (Exception ex) {
            // best effort: swallow to avoid breaking process execution
        }
    }
}
