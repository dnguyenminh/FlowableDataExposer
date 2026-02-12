package vn.com.fecredit.flowable.exposer.flowable;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.entity.SysExposeRequest;
import vn.com.fecredit.flowable.exposer.repository.SysExposeRequestRepository;
import vn.com.fecredit.flowable.exposer.service.CaseDataPersistService;
import vn.com.fecredit.flowable.exposer.service.RequestPersistService;

/**
 * Global, best-effort handler that converts Flowable runtime events into
 * lightweight {@code SysExposeRequest} rows for asynchronous indexing.
 */
@Component
public class GlobalFlowableEventListener implements FlowableEventListener {

    private static final Logger log = LoggerFactory.getLogger(GlobalFlowableEventListener.class);

    @Autowired(required = false)
    private SysExposeRequestRepository requestRepo;

    @Autowired(required = false)
    private CaseDataPersistService caseDataPersistService;

    @Autowired(required = false)
    private RequestPersistService requestPersistService;

    @Autowired(required = false)
    private vn.com.fecredit.flowable.exposer.service.MetadataAnnotator annotator;

    @Override
    public void onEvent(FlowableEvent event) {
        // Protect listener from throwing into Flowable transaction
        if (event == null) return;
        log.debug("GlobalFlowableEventListener.onEvent ENTER: event={}, repoPresent={}", event, (requestRepo != null));

        try {
            log.debug("GlobalFlowableEventListener received event of type {} (class={})", event.getType(), event.getClass().getName());
            log.debug("Event raw: {}", event);

            if (!(event instanceof FlowableEntityEvent)) {
                log.trace("Non-entity event received: type={}", event.getType());
                return;
            }

            Object entity = ((FlowableEntityEvent) event).getEntity();
            log.debug("Entity class: {}", entity == null ? "null" : entity.getClass().getName());
            if (entity == null) {
                log.trace("Entity is null, nothing to do");
                return;
            }

            String cls = entity.getClass().getSimpleName().toLowerCase();

            // Compensating update: when a CMMN CaseInstance is created, try to map recent rows
            if (cls.contains("caseinstance") && caseDataPersistService != null) {
                try {
                    java.lang.reflect.Method mid = entity.getClass().getMethod("getId");
                    Object cid = mid.invoke(entity);
                    if (cid != null) {
                        String newCaseId = String.valueOf(cid);
                        log.info("Detected CaseInstance creation, performing compensating update for caseId={}", newCaseId);
                        try {
                            caseDataPersistService.updateCaseInstanceIdForRecent(newCaseId, java.time.Duration.ofSeconds(5));
                        } catch (Throwable t) {
                            log.warn("Compensating update failed for case {}", newCaseId, t);
                        }
                    }
                } catch (Throwable ignore) {
                    log.debug("Failed to reflectively read CaseInstance id", ignore);
                }
            }

            // PROCESS_STARTED: persist case data automatically when BPMN starts
            if ((cls.contains("execution") || cls.contains("processinstance")) && event.getType() == FlowableEngineEventType.PROCESS_STARTED && caseDataPersistService != null) {
                try {
                    // Try to get variables from the execution using getVariables() instead of getProcessVariables()
                    // At PROCESS_STARTED time, variables aren't populated in getProcessVariables() yet
                    java.util.Map<String, Object> vars = null;
                    try {
                        java.lang.reflect.Method getVars = entity.getClass().getMethod("getVariables");
                        Object varsObj = getVars.invoke(entity);
                        if (varsObj instanceof java.util.Map) {
                            vars = (java.util.Map<String, Object>) varsObj;
                        }
                    } catch (Throwable t) {
                        log.debug("getVariables() failed, trying getProcessVariables()", t);
                    }
                    
                    // Fallback to getProcessVariables if getVariables didn't work
                    if (vars == null || vars.isEmpty()) {
                        try {
                            java.lang.reflect.Method getVars = entity.getClass().getMethod("getProcessVariables");
                            Object varsObj = getVars.invoke(entity);
                            if (varsObj instanceof java.util.Map) {
                                vars = (java.util.Map<String, Object>) varsObj;
                            }
                        } catch (Throwable ignore) {}
                    }
                    
                    if (vars != null && !vars.isEmpty()) {
                        String caseInstanceId = vars.containsKey("caseInstanceId") ? String.valueOf(vars.get("caseInstanceId")) : null;
                        
                        if (caseInstanceId == null) {
                            // Fallback to process instance ID if no caseInstanceId variable
                            java.lang.reflect.Method getPid = entity.getClass().getMethod("getId");
                            caseInstanceId = String.valueOf(getPid.invoke(entity));
                        }
                        
                        // Use canonical entity type for order processes
                        String entityType = "Order";
                        
                        // Ensure minimal annotations and let annotator enrich nested classes if available
                        try {
                            // Conservative defaults for known nested maps so payloads contain expected @class even if resolver isn't available
                            Object custObj = vars.get("customer");
                            if (custObj instanceof java.util.Map) {
                                @SuppressWarnings("unchecked") java.util.Map<String,Object> cm = (java.util.Map<String,Object>) custObj;
                                cm.putIfAbsent("@class", "Customer");
                            }
                            Object itemsObj = vars.get("items");
                            if (itemsObj instanceof Iterable) {
                                for (Object it : (Iterable<?>) itemsObj) {
                                    if (it instanceof java.util.Map) {
                                        @SuppressWarnings("unchecked") java.util.Map<String,Object> im = (java.util.Map<String,Object>) it;
                                        im.putIfAbsent("@class", "Item");
                                    }
                                }
                            }

                            // Ensure meta.priority fallback
                            Object metaObj = vars.get("meta");
                            if (!(metaObj instanceof java.util.Map)) {
                                java.util.Map<String,Object> meta = new java.util.HashMap<>();
                                meta.put("@class", "Meta");
                                meta.put("priority", "HIGH");
                                vars.put("meta", meta);
                            } else {
                                @SuppressWarnings("unchecked") java.util.Map<String,Object> meta = (java.util.Map<String,Object>) metaObj;
                                meta.putIfAbsent("@class", "Meta");
                                meta.putIfAbsent("priority", "HIGH");
                            }

                            // Try to let annotator enrich further when available
                            if (annotator != null) {
                                try { annotator.annotate(vars, "Order"); } catch (Exception ignored) {}
                            }
                        } catch (Throwable ignore) {}

                        // Serialize variables to JSON
                        String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(vars);

                        log.info("PROCESS_STARTED: Persisting case data for caseInstanceId={} entityType={}", caseInstanceId, entityType);
                        caseDataPersistService.persistSysCaseData(caseInstanceId, entityType, payload);

                        // Also create expose request
                        if (requestPersistService != null) {
                            requestPersistService.createRequest(caseInstanceId, entityType, vars.get("initiator") != null ? String.valueOf(vars.get("initiator")) : "system");
                        }
                    }
                } catch (Throwable t) {
                    log.error("Failed to persist case data on PROCESS_STARTED", t);
                }
            }

            // CASE_STARTED: persist case data automatically when CMMN case starts
            if (cls.contains("caseinstance") && "CASE_STARTED".equals(String.valueOf(event.getType())) && caseDataPersistService != null) {
                try {
                    // Get case variables using reflection
                    java.lang.reflect.Method getVars = entity.getClass().getMethod("getCaseVariables");
                    Object varsObj = getVars.invoke(entity);
                    if (varsObj instanceof java.util.Map) {
                        java.util.Map<String, Object> vars = (java.util.Map<String, Object>) varsObj;
                        
                        // Get case instance ID
                        java.lang.reflect.Method getCaseId = entity.getClass().getMethod("getId");
                        String caseInstanceId = String.valueOf(getCaseId.invoke(entity));
                        
                        // Use canonical entity type for order cases
                        String entityType = "Order";
                        
                        // Serialize variables to JSON
                        String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(vars);
                        
                        log.info("CASE_STARTED: Persisting case data for caseInstanceId={} entityType={}", caseInstanceId, entityType);
                        caseDataPersistService.persistSysCaseData(caseInstanceId, entityType, payload);
                        
                        // Also create expose request
                        if (requestPersistService != null) {
                            requestPersistService.createRequest(caseInstanceId, entityType, vars.get("initiator") != null ? String.valueOf(vars.get("initiator")) : "system");
                        }
                    }
                } catch (Throwable t) {
                    log.error("Failed to persist case data on CASE_STARTED", t);
                }
            }

            // PROCESS_COMPLETED: persist final state when process ends
            if ((cls.contains("execution") || cls.contains("processinstance")) && event.getType() == FlowableEngineEventType.PROCESS_COMPLETED && caseDataPersistService != null) {
                try {
                    java.lang.reflect.Method getVars = entity.getClass().getMethod("getProcessVariables");
                    Object varsObj = getVars.invoke(entity);
                    if (varsObj instanceof java.util.Map) {
                        java.util.Map<String, Object> vars = (java.util.Map<String, Object>) varsObj;
                        String caseInstanceId = vars.containsKey("caseInstanceId") ? String.valueOf(vars.get("caseInstanceId")) : null;
                        
                        if (caseInstanceId == null) {
                            java.lang.reflect.Method getPid = entity.getClass().getMethod("getId");
                            caseInstanceId = String.valueOf(getPid.invoke(entity));
                        }
                        
                        // Use canonical entity type for order processes
                        String entityType = "Order";
                        
                        String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(vars);
                        
                        log.info("PROCESS_COMPLETED: Persisting final case data for caseInstanceId={} entityType={}", caseInstanceId, entityType);
                        caseDataPersistService.persistSysCaseData(caseInstanceId, entityType, payload);
                    }
                } catch (Throwable t) {
                    log.error("Failed to persist case data on PROCESS_COMPLETED", t);
                }
            }

            if (entity instanceof Task) {
                Task task = (Task) entity;
                try {
                    log.debug("Task details: id={}, name={}, assignee={}, scopeId={}, scopeDefId={}, repoPresent={}",
                            task.getId(), task.getName(), task.getAssignee(), task.getScopeId(), task.getScopeDefinitionId(), (requestRepo != null));
                    try { log.debug("TaskDefinitionKey={}", task.getTaskDefinitionKey()); } catch (Throwable ignore) {}
                } catch (Throwable t) {
                    log.debug("Failed to log task details", t);
                }

                if (event.getType() == FlowableEngineEventType.TASK_COMPLETED) {
                    String caseInstanceId = null;
                    try {
                        Object vid = null;
                        try {
                            java.lang.reflect.Method mLocal = task.getClass().getMethod("getTaskLocalVariables");
                            Object localVars = mLocal.invoke(task);
                            if (localVars instanceof java.util.Map) vid = ((java.util.Map<?,?>) localVars).get("caseInstanceId");
                        } catch (Throwable ignore) {}
                        if (vid == null) {
                            try {
                                java.lang.reflect.Method mVar = task.getClass().getMethod("getVariable", String.class);
                                Object v = mVar.invoke(task, "caseInstanceId");
                                if (v != null) vid = v;
                            } catch (Throwable ignore) {}
                        }
                        if (vid != null) caseInstanceId = String.valueOf(vid);
                    } catch (Throwable t) {
                        log.debug("Error reading caseInstanceId from task variables", t);
                    }

                    String caseDefinitionId = null;
                    try { caseDefinitionId = task.getScopeDefinitionId(); } catch (Throwable ignore) {}
                    String assignee = null;
                    try { assignee = task.getAssignee(); } catch (Throwable ignore) {}

                    if (caseInstanceId == null) {
                        try { caseInstanceId = task.getScopeId(); } catch (Throwable ignore) {}
                        if (caseInstanceId == null) {
                            try { caseInstanceId = task.getSubScopeId(); } catch (Throwable ignore) {}
                        }
                    }

                    if (caseInstanceId == null) {
                        log.warn("Task {} has no scopeId or caseInstanceId variable; cannot create expose request.", task.getId());
                        return;
                    }

                    if (!isAcceptedTaskType(task)) {
                        log.debug("Task {} is not an accepted task type for exposure; skipping.", task.getId());
                        return;
                    }

                    String entityType = null;
                    if (caseDefinitionId != null && caseDefinitionId.contains(":")) {
                        entityType = caseDefinitionId.split(":")[0];
                    }

                    SysExposeRequest req = new SysExposeRequest();
                    req.setCaseInstanceId(caseInstanceId);
                    req.setEntityType(entityType);
                    req.setRequestedBy(assignee);

                    if (caseDataPersistService == null && requestPersistService == null && requestRepo == null) {
                        log.warn("No persistence services or repository available; skipping persist for case {}. Request object: {}", caseInstanceId, req);
                    } else {
                        try {
                            if (caseDataPersistService != null) {
                                try {
                                    caseDataPersistService.persistSysCaseData(caseInstanceId, entityType, "{}");
                                    log.info("persistSysCaseData called for case {}", caseInstanceId);
                                } catch (Throwable t) {
                                    log.error("CaseDataPersistService.persistSysCaseData failed for case {}", caseInstanceId, t);
                                }
                            }

                            if (requestPersistService != null) {
                                try {
                                    requestPersistService.createRequest(caseInstanceId, entityType, assignee);
                                    log.info("Created expose request via RequestPersistService for case {}", caseInstanceId);
                                } catch (Throwable t) {
                                    log.error("RequestPersistService.createRequest failed for case {}", caseInstanceId, t);
                                }
                            } else if (requestRepo != null) {
                                SysExposeRequest saved = requestRepo.save(req);
                                log.info("Created expose request id={} for case {} from task completion event.", saved.getId(), caseInstanceId);
                            }
                        } catch (Throwable saveEx) {
                            log.error("Failed to persist SysExposeRequest for case {}", caseInstanceId, saveEx);
                        }
                    }
                } else {
                    log.trace("Task event {} received for task {} - no expose request created.", event.getType(), task.getId());
                }
            } else {
                log.trace("Non-task entity event: {}", entity);
            }
        } catch (Exception ex) {
            log.error("Error in GlobalFlowableEventListener while processing event " + event, ex);
        }
    }

    @Override
    public boolean isFailOnException() {
        // Return false to ensure that an exception in the listener does not rollback the main Flowable transaction.
        return false;
    }

    /**
     * Determine whether the given task is of an accepted type for exposure.
     */
    private boolean isAcceptedTaskType(Task task) {
        if (task == null) return false;
        try {
            if (task.getAssignee() != null) return true;

            try {
                java.lang.reflect.Method mc = task.getClass().getMethod("getCategory");
                Object cat = mc.invoke(task);
                if (cat instanceof String) {
                    String s = ((String) cat).toLowerCase();
                    if (s.contains("user") || s.contains("wait")) return true;
                }
            } catch (NoSuchMethodException ignored) {}

            try {
                java.lang.reflect.Method mk = task.getClass().getMethod("getTaskDefinitionKey");
                Object key = mk.invoke(task);
                if (key instanceof String) {
                    String k = ((String) key).toLowerCase();
                    if (k.contains("user") || k.contains("wait") || k.contains("payment") || k.contains("approve")) return true;
                }
            } catch (NoSuchMethodException ignored) {}

            String cls = task.getClass().getSimpleName().toLowerCase();
            if (cls.contains("usertask") || cls.contains("taskentity") || cls.contains("wait")) return true;
        } catch (Throwable t) {
            try { log.debug("Error while determining task type for task {}", task.getId(), t); } catch (Throwable ignored) { log.debug("Error while determining task type (no id available)", t); }
        }
        return false;
    }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        // Set to false to fire the event immediately, not tied to transaction state.
        return false;
    }

    @Override
    public String getOnTransaction() {
        // Not used as isFireOnTransactionLifecycleEvent is false.
        return null;
    }
}
