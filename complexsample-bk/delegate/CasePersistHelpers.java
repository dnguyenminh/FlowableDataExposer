package vn.com.fecredit.complexsample.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.delegate.DelegateExecution;
import vn.com.fecredit.complexsample.service.MetadataAnnotator;

import java.util.*;

public final class CasePersistHelpers {
    private CasePersistHelpers() {}

    public static Map<String, Object> copyVariables(DelegateExecution execution) {
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

    public static String resolveCaseInstanceId(DelegateExecution execution, Map<String, Object> vars) {
        try {
            if (vars != null) {
                Object v = vars.get("caseInstanceId");
                if (v == null) v = vars.get("caseId");
                if (v == null) v = vars.get("scopeId");
                if (v == null) v = vars.get("parentId");
                if (v != null) return String.valueOf(v);
            }
            try {
                Object ev = execution.getVariable("caseInstanceId");
                if (ev != null) return String.valueOf(ev);
            } catch (Exception ignored) {}
            try {
                Object ev = execution.getVariable("caseId");
                if (ev != null) return String.valueOf(ev);
            } catch (Exception ignored) {}
            return execution.getProcessInstanceId();
        } catch (Throwable t) {
            return execution.getProcessInstanceId();
        }
    }

    public static void ensureClassAnnotations(Map<String, Object> vars) {
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

    public static void populateFlowableMetadata(DelegateExecution execution, Map<String, Object> vars) {
        try {
            try {
                var m = execution.getClass().getMethod("getStartTime");
                Object st = m.invoke(execution);
                if (st != null) vars.putIfAbsent("createTime", String.valueOf(st));
            } catch (NoSuchMethodException ignored) {}

            try {
                var m2 = execution.getClass().getMethod("getStartUserId");
                Object su = m2.invoke(execution);
                if (su != null) vars.putIfAbsent("startUserId", String.valueOf(su));
            } catch (NoSuchMethodException ignored) {}

            try {
                var m3 = execution.getClass().getMethod("getBusinessKey");
                Object bk = m3.invoke(execution);
                if (bk != null) vars.putIfAbsent("businessKey", String.valueOf(bk));
            } catch (NoSuchMethodException ignored) {}

            try {
                var m4 = execution.getClass().getMethod("getTenantId");
                Object tid = m4.invoke(execution);
                if (tid != null) vars.putIfAbsent("tenantId", String.valueOf(tid));
            } catch (NoSuchMethodException ignored) {}

            try {
                var m5 = execution.getClass().getMethod("getProcessDefinitionId");
                Object pd = m5.invoke(execution);
                if (pd != null) vars.putIfAbsent("processDefinitionId", String.valueOf(pd));
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {
            // best-effort only
        }
    }

    public static void putClassIfMap(Map<String, Object> vars, String key, String className) {
        Object o = vars.get(key);
        if (o instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            m.putIfAbsent("@class", className);
        }
    }

    public static void safeAnnotate(MetadataAnnotator annotator, Map<String, Object> vars) {
        if (annotator == null) return;
        try {
            annotator.annotate(vars, "Order");
        } catch (Exception ignored) {
            // best-effort
        }
    }

    public static String stringify(ObjectMapper om, Map<String, Object> vars) {
        try {
            return om.writeValueAsString(vars);
        } catch (Exception e) {
            return vars.toString();
        }
    }
}
