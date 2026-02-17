package vn.com.fecredit.complexsample.flowable;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.fecredit.complexsample.service.MetadataAnnotator;
import vn.com.fecredit.complexsample.service.CaseDataPersistService;
import vn.com.fecredit.complexsample.service.RequestPersistService;

@Component
public class FlowableEventDelegator {

    private static final Logger log = LoggerFactory.getLogger(FlowableEventDelegator.class);

    @Autowired(required = false)
    private CaseDataPersistService caseDataPersistService;

    @Autowired(required = false)
    private RequestPersistService requestPersistService;

    @Autowired(required = false)
    private MetadataAnnotator annotator;

    @Autowired(required = false)
    private TaskExposeHandler taskExposeHandler;

    public void handle(FlowableEvent event, Object entity, String cls) {
        try {
            if ((cls.contains("execution") || cls.contains("processinstance")) && event.getType() == FlowableEngineEventType.PROCESS_STARTED) {
                handleProcessStarted(entity);
                return;
            }
            if (cls.contains("caseinstance") && "CASE_STARTED".equals(String.valueOf(event.getType()))) {
                handleCaseStarted(entity);
                return;
            }
            if ((cls.contains("execution") || cls.contains("processinstance")) && event.getType() == FlowableEngineEventType.PROCESS_COMPLETED) {
                handleProcessCompleted(entity);
                return;
            }
            if (entity instanceof Task) {
                handleTask((Task) entity, (FlowableEngineEventType) event.getType());
            }
        } catch (Throwable t) {
            log.error("FlowableEventDelegator failed to handle event", t);
        }
    }

    private void handleProcessStarted(Object entity) {
        if (caseDataPersistService == null) return;
        try {
            java.util.Map<String, Object> vars = extractMap(entity, "getVariables", "getProcessVariables");
            if (vars == null || vars.isEmpty()) return;

            String caseInstanceId = vars.containsKey("caseInstanceId") ? String.valueOf(vars.get("caseInstanceId")) : null;
            if (caseInstanceId == null) caseInstanceId = String.valueOf(reflect(entity, "getId"));

            String entityType = "Order";

            enrichMinimalAnnotations(vars);

            try { if (annotator != null) annotator.annotate(vars, "Order"); } catch (Exception ignored) {}

            String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(vars);
            log.info("PROCESS_STARTED: Persisting case data for caseInstanceId={} entityType={}", caseInstanceId, entityType);
            caseDataPersistService.persistSysCaseData(caseInstanceId, entityType, payload);
            if (requestPersistService != null) requestPersistService.createRequest(caseInstanceId, entityType, vars.get("initiator") != null ? String.valueOf(vars.get("initiator")) : "system");
        } catch (Throwable t) {
            log.error("Failed to persist case data on PROCESS_STARTED", t);
        }
    }

    private void handleCaseStarted(Object entity) {
        if (caseDataPersistService == null) return;
        try {
            Object varsObj = reflect(entity, "getCaseVariables");
            if (!(varsObj instanceof java.util.Map)) return;
            @SuppressWarnings("unchecked") java.util.Map<String, Object> vars = (java.util.Map<String, Object>) varsObj;
            String caseInstanceId = String.valueOf(reflect(entity, "getId"));
            String entityType = "Order";
            String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(vars);
            log.info("CASE_STARTED: Persisting case data for caseInstanceId={} entityType={}", caseInstanceId, entityType);
            caseDataPersistService.persistSysCaseData(caseInstanceId, entityType, payload);
            if (requestPersistService != null) requestPersistService.createRequest(caseInstanceId, entityType, vars.get("initiator") != null ? String.valueOf(vars.get("initiator")) : "system");
        } catch (Throwable t) {
            log.error("Failed to persist case data on CASE_STARTED", t);
        }
    }

    private void handleProcessCompleted(Object entity) {
        if (caseDataPersistService == null) return;
        try {
            java.util.Map<String, Object> vars = extractMap(entity, "getProcessVariables");
            if (vars == null || vars.isEmpty()) return;
            String caseInstanceId = vars.containsKey("caseInstanceId") ? String.valueOf(vars.get("caseInstanceId")) : String.valueOf(reflect(entity, "getId"));
            String entityType = "Order";
            String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(vars);
            log.info("PROCESS_COMPLETED: Persisting final case data for caseInstanceId={} entityType={}", caseInstanceId, entityType);
            caseDataPersistService.persistSysCaseData(caseInstanceId, entityType, payload);
        } catch (Throwable t) {
            log.error("Failed to persist case data on PROCESS_COMPLETED", t);
        }
    }

    private void handleTask(Task task, FlowableEngineEventType eventType) {
        if (taskExposeHandler != null) {
            try { taskExposeHandler.handle(task, eventType); } catch (Throwable t) { log.error("TaskExposeHandler failed", t); }
        }
    }

    // small helpers
    private Object reflect(Object target, String method) {
        try { java.lang.reflect.Method m = target.getClass().getMethod(method); return m.invoke(target); } catch (Throwable t) { return null; }
    }

    private java.util.Map<String,Object> extractMap(Object target, String... methods) {
        for (String mname : methods) {
            try {
                java.lang.reflect.Method m = target.getClass().getMethod(mname);
                Object ro = m.invoke(target);
                if (ro instanceof java.util.Map) return (java.util.Map<String,Object>) ro;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void enrichMinimalAnnotations(java.util.Map<String,Object> vars) {
        try {
            Object custObj = vars.get("customer");
            if (custObj instanceof java.util.Map) {
                @SuppressWarnings("unchecked") java.util.Map<String,Object> cm = (java.util.Map<String,Object>) custObj;
                cm.putIfAbsent("@class", "Customer");
            }
            Object itemsObj = vars.get("items");
            if (itemsObj instanceof Iterable) {
                for (Object it : (Iterable<?>) itemsObj) if (it instanceof java.util.Map) {
                    @SuppressWarnings("unchecked") java.util.Map<String,Object> im = (java.util.Map<String,Object>) it;
                    im.putIfAbsent("@class", "Item");
                }
            }
            Object metaObj = vars.get("meta");
            if (!(metaObj instanceof java.util.Map)) {
                java.util.Map<String,Object> meta = new java.util.HashMap<>(); meta.put("@class","Meta"); meta.put("priority","HIGH"); vars.put("meta",meta);
            } else {
                @SuppressWarnings("unchecked") java.util.Map<String,Object> meta = (java.util.Map<String,Object>) metaObj; meta.putIfAbsent("@class","Meta"); meta.putIfAbsent("priority","HIGH");
            }
        } catch (Throwable ignored) {}
    }
}
