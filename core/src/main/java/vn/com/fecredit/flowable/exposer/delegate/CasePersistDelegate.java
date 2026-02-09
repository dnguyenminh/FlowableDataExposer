package vn.com.fecredit.flowable.exposer.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.service.MetadataAnnotator;
import vn.com.fecredit.flowable.exposer.service.CaseDataPersistService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(CasePersistDelegate.class);

    @Autowired
    private ObjectMapper om;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MetadataAnnotator annotator;

    @Autowired
    private CaseDataPersistService persistService;
    @Autowired
    private vn.com.fecredit.flowable.exposer.service.RequestPersistService requestPersistService;

    /**
     * Entry point invoked by Flowable. Kept short by delegating to helpers so
     * each helper stays well within the 20-line rule and is easier to unit-test.
     */
    @Override
    public void execute(DelegateExecution execution) {
        Map<String, Object> vars = copyVariables(execution);
        String caseInstanceId = resolveCaseInstanceId(execution, vars);

        // enrich with Flowable-derived canonical fields (createTime, startUserId, businessKey, tenantId)
        populateFlowableMetadata(execution, vars);

        ensureClassAnnotations(vars);
        safeAnnotate(vars);

        // Diagnostic: visible in test output for deterministic E2E assertions
        logger.info("CasePersistDelegate vars before persist (caseInstanceId={}): {}", caseInstanceId, vars);

        String payload = stringify(vars);
        // persist in a separate transaction so process rollbacks do not remove the blob
        try {
            // persist with service computing version and setting initial status
            persistService.persistSysCaseData(caseInstanceId, "Order", payload);
            // create a lightweight expose request so the async worker will pick this up
            try {
                // persist request in its own transaction so it is visible to the worker even if the process
                // outer transaction rolls back. Best-effort: do not throw on failure.
                logger.info("CasePersistDelegate calling RequestPersistService.createRequest(caseInstanceId={}, entityType={})", caseInstanceId, "Order");
                requestPersistService.createRequest(caseInstanceId, "Order", null);
                logger.info("CasePersistDelegate created sys_expose_request (REQUIRES_NEW) for {}", caseInstanceId);
            } catch (Throwable t) {
                logger.warn("CasePersistDelegate: failed to create sys_expose_request for {}: {}", caseInstanceId, t.getMessage());
            }
        } catch (Exception ex) {
            // Log full stacktrace so the cause (schema, constraint, driver) is visible during debugging
            logger.warn("Failed to persist case blob for {}:", caseInstanceId, ex);
        }
    }

    private String resolveCaseInstanceId(DelegateExecution execution, Map<String, Object> vars) {
        try {
            // Prefer variables explicitly carrying the CMMN case id (from CMMN->BPMN mapping or propagated vars)
            if (vars != null) {
                Object v = vars.get("caseInstanceId");
                if (v == null) v = vars.get("caseId");
                if (v == null) v = vars.get("scopeId");
                if (v == null) v = vars.get("parentId");
                if (v != null) {
                    logger.debug("resolveCaseInstanceId: using vars-based id={} varsKeys={}", v, vars.keySet());
                    return String.valueOf(v);
                }
            }
            // Try execution variables next
            try {
                Object ev = execution.getVariable("caseInstanceId");
                if (ev != null) {
                    logger.debug("resolveCaseInstanceId: using execution variable caseInstanceId={}", ev);
                    return String.valueOf(ev);
                }
            } catch (Exception ignored) {}
            try {
                Object ev = execution.getVariable("caseId");
                if (ev != null) {
                    logger.debug("resolveCaseInstanceId: using execution variable caseId={}", ev);
                    return String.valueOf(ev);
                }
            } catch (Exception ignored) {}
            // Fallback to the BPMN process instance id (legacy behavior)
            String fallback = execution.getProcessInstanceId();
            logger.debug("resolveCaseInstanceId: falling back to processInstanceId={}", fallback);
            return fallback;
        } catch (Throwable t) {
            logger.warn("resolveCaseInstanceId: unexpected error while resolving case id, falling back to processInstanceId", t);
            return execution.getProcessInstanceId();
        }
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

    /**
     * Best-effort extraction of Flowable execution metadata into the
     * variable map so it becomes part of the persisted snapshot.
     *
     * This uses reflection to avoid hard coupling to specific Flowable
     * API versions and to remain test-friendly.
     */
    private void populateFlowableMetadata(org.flowable.engine.delegate.DelegateExecution execution, Map<String, Object> vars) {
        try {
            // start time / createTime
            try {
                var m = execution.getClass().getMethod("getStartTime");
                Object st = m.invoke(execution);
                if (st != null) vars.putIfAbsent("createTime", String.valueOf(st));
            } catch (NoSuchMethodException ignored) {}

            // start user
            try {
                var m2 = execution.getClass().getMethod("getStartUserId");
                Object su = m2.invoke(execution);
                if (su != null) vars.putIfAbsent("startUserId", String.valueOf(su));
            } catch (NoSuchMethodException ignored) {}

            // business key
            try {
                var m3 = execution.getClass().getMethod("getBusinessKey");
                Object bk = m3.invoke(execution);
                if (bk != null) vars.putIfAbsent("businessKey", String.valueOf(bk));
            } catch (NoSuchMethodException ignored) {}

            // tenant id
            try {
                var m4 = execution.getClass().getMethod("getTenantId");
                Object tid = m4.invoke(execution);
                if (tid != null) vars.putIfAbsent("tenantId", String.valueOf(tid));
            } catch (NoSuchMethodException ignored) {}

            // processDefinitionId (useful for inferring entityType)
            try {
                var m5 = execution.getClass().getMethod("getProcessDefinitionId");
                Object pd = m5.invoke(execution);
                if (pd != null) vars.putIfAbsent("processDefinitionId", String.valueOf(pd));
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {
            // best-effort only
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
