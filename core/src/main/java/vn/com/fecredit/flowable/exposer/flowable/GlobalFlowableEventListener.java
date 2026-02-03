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

/**
 * Global, best-effort handler that converts Flowable runtime events into
 * lightweight {@code SysExposeRequest} rows for asynchronous indexing.
 *
 * <p>By implementing {@code FlowableEventListener} and being a {@code @Component},
 * the Flowable Spring Boot starter automatically registers this as a global listener.</p>
 */
@Component
public class GlobalFlowableEventListener implements FlowableEventListener {

    private static final Logger log = LoggerFactory.getLogger(GlobalFlowableEventListener.class);

    @Autowired
    private SysExposeRequestRepository requestRepo;

    @Override
    public void onEvent(FlowableEvent event) {
        try {
            if (event == null) {
                return;
            }
            log.debug("GlobalFlowableEventListener received event of type {}", event.getType());

            // We are interested in events that signify a change in the case, like task completions.
            // TASK_COMPLETED is a good candidate.
            if (event.getType() != FlowableEngineEventType.TASK_COMPLETED) {
                log.trace("Ignoring event of type {} as it is not relevant for re-indexing.", event.getType());
                return;
            }

            if (!(event instanceof FlowableEntityEvent)) {
                log.debug("Event is not a FlowableEntityEvent; ignoring.");
                return;
            }

            Object entity = ((FlowableEntityEvent) event).getEntity();
            if (!(entity instanceof Task)) {
                log.debug("Event entity is not a Task; ignoring. Entity is {}", entity.getClass().getName());
                return;
            }

            Task task = (Task) entity;
            // For CMMN cases, the scopeId is the caseInstanceId. This is more reliable than getProcessInstanceId().
            String caseInstanceId = task.getScopeId();
            String caseDefinitionId = task.getScopeDefinitionId(); // Corresponds to the CMMN case definition
            String assignee = task.getAssignee();

            if (caseInstanceId == null) {
                log.warn("Task {} has no scopeId (caseInstanceId); cannot create expose request.", task.getId());
                return;
            }

            String entityType = null;
            if (caseDefinitionId != null && caseDefinitionId.contains(":")) {
                entityType = caseDefinitionId.split(":")[0];
            }

            SysExposeRequest req = new SysExposeRequest();
            req.setCaseInstanceId(caseInstanceId);
            req.setEntityType(entityType);
            // In some scenarios, the user completing the task is not the assignee.
            // For simplicity, we use assignee here. A more complex implementation might get the user from the security context.
            req.setRequestedBy(assignee);
            requestRepo.save(req);
            log.info("Created expose request for case {} from task completion event.", caseInstanceId);

        } catch (Exception ex) {
            // As per isFailOnException=false, we log the error but do not let it interrupt the Flowable transaction.
            log.error("Error in GlobalFlowableEventListener while processing event " + event, ex);
        }
    }

    @Override
    public boolean isFailOnException() {
        // Return false to ensure that an exception in the listener does not rollback the main Flowable transaction.
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