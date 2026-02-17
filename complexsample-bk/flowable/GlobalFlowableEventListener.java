package vn.com.fecredit.complexsample.flowable;

import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Thin global listener that delegates heavy work to smaller collaborators.
 */
@Component
public class GlobalFlowableEventListener implements FlowableEventListener {

    private static final Logger log = LoggerFactory.getLogger(GlobalFlowableEventListener.class);

    @Autowired(required = false)
    private FlowableEventDelegator flowableEventDelegator;

    @Autowired(required = false)
    private TaskExposeHandler taskExposeHandler;

    @Override
    public void onEvent(FlowableEvent event) {
        if (event == null) return;

        if (!(event instanceof FlowableEntityEvent)) {
            log.trace("Non-entity event received: {}", event == null ? "<null>" : event.getType());
            return;
        }

        Object entity = ((FlowableEntityEvent) event).getEntity();
        if (entity == null) return;

        String cls = entity.getClass().getSimpleName().toLowerCase();

        try {
            if (flowableEventDelegator != null) {
                flowableEventDelegator.handle(event, entity, cls);
                return;
            }

            // Fallback: very small behavior for tasks when delegator not present
            if (entity instanceof Task && taskExposeHandler != null) {
                try {
                    taskExposeHandler.handle((Task) entity, (org.flowable.common.engine.api.delegate.event.FlowableEngineEventType) event.getType());
                } catch (Throwable t) {
                    log.error("Fallback task handler failed", t);
                }
            }
        } catch (Throwable t) {
            log.error("Error in GlobalFlowableEventListener while delegating event {}", event, t);
        }
    }

    @Override
    public boolean isFailOnException() { return false; }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() { return false; }

    @Override
    public String getOnTransaction() { return null; }
}
