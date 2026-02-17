package vn.com.fecredit.complexsample.flowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.fecredit.complexsample.repository.SysExposeRequestRepository;
import vn.com.fecredit.complexsample.service.MetadataAnnotator;
import vn.com.fecredit.complexsample.service.CaseDataPersistService;
import vn.com.fecredit.complexsample.service.RequestPersistService;

import java.util.Map;

@Component
public class ProcessCaseHandler {
    private static final Logger log = LoggerFactory.getLogger(ProcessCaseHandler.class);

    @Autowired(required = false)
    private CaseDataPersistService caseDataPersistService;

    @Autowired(required = false)
    private RequestPersistService requestPersistService;

    @Autowired(required = false)
    private SysExposeRequestRepository requestRepo;

    @Autowired(required = false)
    private MetadataAnnotator annotator;

    private final ObjectMapper om = new ObjectMapper();

    public void handleProcessStarted(Object entity) {
        Map<String, Object> vars = extractVariables(entity);
        if (vars == null || vars.isEmpty()) return;
        String caseInstanceId = resolveCaseInstanceId(entity, vars);
        if (caseInstanceId == null) return;
        annotateConservative(vars);
        persistAndRequest(caseInstanceId, "Order", vars);
    }

    public void handleCaseStarted(Object entity) {
        Map<String, Object> vars = extractCaseVariables(entity);
        if (vars == null) return;
        String caseInstanceId = resolveIdReflectively(entity);
        if (caseInstanceId == null) return;
        persistAndRequest(caseInstanceId, "Order", vars);
    }

    public void handleProcessCompleted(Object entity) {
        Map<String, Object> vars = extractProcessVariables(entity);
        if (vars == null) return;
        String caseInstanceId = resolveCaseInstanceId(entity, vars);
        if (caseInstanceId == null) return;
        persistAndRequest(caseInstanceId, "Order", vars);
    }

    private Map<String, Object> extractVariables(Object entity) {
        try {
            var m = entity.getClass().getMethod("getVariables");
            Object o = m.invoke(entity);
            if (o instanceof Map) return (Map<String, Object>) o;
        } catch (Throwable t) { log.debug("getVariables failed", t); }
        try {
            var m = entity.getClass().getMethod("getProcessVariables");
            Object o = m.invoke(entity);
            if (o instanceof Map) return (Map<String, Object>) o;
        } catch (Throwable ignore) {}
        return null;
    }

    private Map<String, Object> extractProcessVariables(Object entity) {
        try {
            var m = entity.getClass().getMethod("getProcessVariables");
            Object o = m.invoke(entity);
            if (o instanceof Map) return (Map<String, Object>) o;
        } catch (Throwable ignore) {}
        return null;
    }

    private Map<String, Object> extractCaseVariables(Object entity) {
        try {
            var m = entity.getClass().getMethod("getCaseVariables");
            Object o = m.invoke(entity);
            if (o instanceof Map) return (Map<String, Object>) o;
        } catch (Throwable ignore) {}
        return null;
    }

    private String resolveCaseInstanceId(Object entity, Map<String,Object> vars) {
        try {
            if (vars.containsKey("caseInstanceId")) return String.valueOf(vars.get("caseInstanceId"));
            return resolveIdReflectively(entity);
        } catch (Throwable t) { return null; }
    }

    private String resolveIdReflectively(Object entity) {
        try {
            var m = entity.getClass().getMethod("getId");
            Object id = m.invoke(entity);
            return id == null ? null : String.valueOf(id);
        } catch (Throwable ignore) { return null; }
    }

    private void annotateConservative(Map<String,Object> vars) {
        try {
            Object custObj = vars.get("customer");
            if (custObj instanceof Map) ((Map)custObj).putIfAbsent("@class", "Customer");
            Object itemsObj = vars.get("items");
            if (itemsObj instanceof Iterable) for (Object it : (Iterable<?>) itemsObj) if (it instanceof Map) ((Map)it).putIfAbsent("@class", "Item");
            Object metaObj = vars.get("meta");
            if (!(metaObj instanceof Map)) {
                var meta = new java.util.HashMap<String,Object>(); meta.put("@class","Meta"); meta.put("priority","HIGH"); vars.put("meta", meta);
            } else ((Map)metaObj).putIfAbsent("@class","Meta");
            if (annotator != null) try { annotator.annotate(vars, "Order"); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private void persistAndRequest(String caseInstanceId, String entityType, Map<String,Object> vars) {
        try {
            String payload = om.writeValueAsString(vars);
            log.info("Persisting case data for caseInstanceId={} entityType={}", caseInstanceId, entityType);
            if (caseDataPersistService != null) caseDataPersistService.persistSysCaseData(caseInstanceId, entityType, payload);
            if (requestPersistService != null) requestPersistService.createRequest(caseInstanceId, entityType, vars.get("initiator") != null ? String.valueOf(vars.get("initiator")) : "system");
        } catch (Throwable t) { log.error("Persist/request failed for {}", caseInstanceId, t); }
    }
}
