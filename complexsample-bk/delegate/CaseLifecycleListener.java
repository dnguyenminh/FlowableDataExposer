package vn.com.fecredit.complexsample.delegate;

import org.flowable.task.service.delegate.DelegateTask;
import org.flowable.task.service.delegate.TaskListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.fecredit.complexsample.entity.SysExposeRequest;
import vn.com.fecredit.complexsample.repository.SysExposeRequestRepository;

/**
 * Listener invoked during case/task lifecycle to capture a lightweight
 * SysExposeRequest for downstream processing (indexing/reindexing).
 *
 * Design notes:
 * - non-fatal: listener errors are swallowed to avoid breaking Flowable tasks
 * - best-effort entityType inference from processDefinitionId is performed
 */
@Component("caseLifecycleListener")
public class CaseLifecycleListener implements TaskListener {

    @Autowired
    private SysExposeRequestRepository requestRepo;

    /**
     * Task listener entry point. Records a SysExposeRequest when a task is
     * associated with a case/process instance.
     */
    @Override
    public void notify(DelegateTask delegateTask) {
        try {
            handleNotify(delegateTask);
        } catch (Exception ex) {
            // non-fatal: do not fail the BPMN/CMMN task because of listener errors
            logError(ex);
        }
    }

    /** Small, focused handler extracted to keep notify() concise and testable. */
    private void handleNotify(DelegateTask delegateTask) {
        String caseInstanceId = extractCaseInstanceId(delegateTask);
        if (caseInstanceId == null) return;

        String entityType = inferEntityType(delegateTask == null ? null : delegateTask.getProcessDefinitionId());
        createAndSaveRequest(caseInstanceId, entityType, delegateTask);
    }

    /**
     * Extract the case/process instance id from the delegate (null-safe).
     */
    private String extractCaseInstanceId(DelegateTask delegateTask) {
        return delegateTask == null ? null : delegateTask.getProcessInstanceId();
    }

    /**
     * Best-effort inference of entityType from a processDefinitionId.
     * Expected format: key:version:id — returns the 'key' portion or null.
     */
    private String inferEntityType(String processDefinitionId) {
        if (processDefinitionId == null) return null;
        if (!processDefinitionId.contains(":")) return null;
        return processDefinitionId.split(":", 2)[0];
    }

    /**
     * Build and persist a SysExposeRequest from the provided inputs.
     */
    private void createAndSaveRequest(String caseInstanceId, String entityType, DelegateTask delegateTask) {
        SysExposeRequest req = new SysExposeRequest();
        req.setCaseInstanceId(caseInstanceId);
        req.setEntityType(entityType);
        req.setRequestedBy(delegateTask == null ? null : delegateTask.getAssignee());
        requestRepo.save(req);
    }

    /** Centralize error reporting so behaviour is easy to change/test. */
    private void logError(Exception ex) {
        try {
            System.err.println("CaseLifecycleListener failed: " + ex.getMessage());
        } catch (Throwable ignore) {
            // absolutely never throw from the listener's error path
        }
    }
}

