package vn.com.fecredit.flowable.exposer.flowable;

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
 * <p>Important: this class is invoked reflectively (or via a proxy) so it
 * must tolerate a variety of event implementations and never throw.</p>
 */
@Component
public class GlobalFlowableEventListener {

    private static final Logger log = LoggerFactory.getLogger(GlobalFlowableEventListener.class);

    @Autowired
    private SysExposeRequestRepository requestRepo;

    // Generic handler invoked reflectively by a proxy registered with Flowable's dispatcher.
    public void handleEvent(Object event) {
        try {
            if (event == null) return;

            // Reflectively obtain event type name if available (best-effort)
            String typeName = null;
            try {
                var mType = event.getClass().getMethod("getType");
                Object t = mType.invoke(event);
                if (t != null) typeName = String.valueOf(t);
            } catch (NoSuchMethodException ignored) {}

            // only proceed for task events (best-effort: match name contains TASK)
            if (typeName != null && !(typeName.contains("TASK"))) return;

            // Extract entity (task) via reflection if present
            Object ent = null;
            try {
                var mEntity = event.getClass().getMethod("getEntity");
                ent = mEntity.invoke(event);
            } catch (NoSuchMethodException ignored) {}
            if (ent == null) return;

            // Use reflection to obtain processInstanceId, processDefinitionId, assignee safely
            String caseInstanceId = null;
            String processDefinitionId = null;
            String assignee = null;
            try {
                var m = ent.getClass().getMethod("getProcessInstanceId");
                Object v = m.invoke(ent);
                if (v != null) caseInstanceId = String.valueOf(v);
            } catch (NoSuchMethodException ignored) {}
            try {
                var m2 = ent.getClass().getMethod("getProcessDefinitionId");
                Object v2 = m2.invoke(ent);
                if (v2 != null) processDefinitionId = String.valueOf(v2);
            } catch (NoSuchMethodException ignored) {}
            try {
                var m3 = ent.getClass().getMethod("getAssignee");
                Object v3 = m3.invoke(ent);
                if (v3 != null) assignee = String.valueOf(v3);
            } catch (NoSuchMethodException ignored) {}

            if (caseInstanceId == null) return;

            String entityType = null;
            if (processDefinitionId != null && processDefinitionId.contains(":")) {
                entityType = processDefinitionId.split(":")[0];
            }

            SysExposeRequest req = new SysExposeRequest();
            req.setCaseInstanceId(caseInstanceId);
            req.setEntityType(entityType);
            req.setRequestedBy(assignee);
            requestRepo.save(req);
            log.debug("Created expose request for case {} entityType={} assignee={}", caseInstanceId, entityType, assignee);
        } catch (Exception ex) {
            log.error("GlobalFlowableEventListener error", ex);
        }
    }
}
