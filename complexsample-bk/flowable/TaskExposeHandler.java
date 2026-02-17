package vn.com.fecredit.complexsample.flowable;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.fecredit.complexsample.entity.SysExposeRequest;
import vn.com.fecredit.complexsample.repository.SysExposeRequestRepository;
import vn.com.fecredit.complexsample.service.CaseDataPersistService;
import vn.com.fecredit.complexsample.service.RequestPersistService;

@Component
public class TaskExposeHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskExposeHandler.class);

    @Autowired(required = false)
    private SysExposeRequestRepository requestRepo;

    @Autowired(required = false)
    private CaseDataPersistService caseDataPersistService;

    @Autowired(required = false)
    private RequestPersistService requestPersistService;

    public void handle(Task task, FlowableEngineEventType eventType) {
        if (task == null) return;
        if (eventType != FlowableEngineEventType.TASK_COMPLETED) {
            log.trace("Task event {} received for task {} - no expose request created.", eventType, task.getId());
            return;
        }

        String caseInstanceId = extractCaseInstanceId(task);
        if (caseInstanceId == null) {
            log.warn("Task {} has no scopeId or caseInstanceId variable; cannot create expose request.", safeId(task));
            return;
        }

        if (!TaskTypeUtils.isAcceptedTaskType(task)) {
            log.debug("Task {} is not an accepted task type for exposure; skipping.", safeId(task));
            return;
        }

        String caseDefinitionId = safe(() -> task.getScopeDefinitionId());
        String assignee = safe(() -> task.getAssignee());
        String entityType = null;
        if (caseDefinitionId != null && caseDefinitionId.contains(":")) {
            entityType = caseDefinitionId.split(":")[0];
        }

        SysExposeRequest req = new SysExposeRequest();
        req.setCaseInstanceId(caseInstanceId);
        req.setEntityType(entityType);
        req.setRequestedBy(assignee);

        persistRequest(req, caseInstanceId, entityType, assignee);
    }

    private void persistRequest(SysExposeRequest req, String caseInstanceId, String entityType, String assignee) {
        if (caseDataPersistService == null && requestPersistService == null && requestRepo == null) {
            log.warn("No persistence services or repository available; skipping persist for case {}. Request object: {}", caseInstanceId, req);
            return;
        }

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

    private String extractCaseInstanceId(Task task) {
        try {
            Object vid = null;
            try {
                java.lang.reflect.Method mLocal = task.getClass().getMethod("getTaskLocalVariables");
                Object localVars = mLocal.invoke(task);
                if (localVars instanceof java.util.Map) vid = ((java.util.Map<?, ?>) localVars).get("caseInstanceId");
            } catch (Throwable ignore) {
            }
            if (vid == null) {
                try {
                    java.lang.reflect.Method mVar = task.getClass().getMethod("getVariable", String.class);
                    Object v = mVar.invoke(task, "caseInstanceId");
                    if (v != null) vid = v;
                } catch (Throwable ignore) {
                }
            }
            String caseInstanceId = vid != null ? String.valueOf(vid) : null;
            if (caseInstanceId == null) {
                try {
                    caseInstanceId = task.getScopeId();
                } catch (Throwable ignore) {
                }
                if (caseInstanceId == null) {
                    try { caseInstanceId = task.getSubScopeId(); } catch (Throwable ignore) {}
                }
            }
            return caseInstanceId;
        } catch (Throwable t) {
            log.debug("Error reading caseInstanceId from task variables", t);
            return null;
        }
    }

    private String safeId(Task task) {
        try { return task.getId(); } catch (Throwable ignored) { return "<unknown>"; }
    }

    private <T> T safe(java.util.concurrent.Callable<T> c) {
        try { return c.call(); } catch (Throwable ignored) { return null; }
    }
}
