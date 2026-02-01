package vn.com.fecredit.flowable.exposer.delegate;

import org.flowable.task.service.delegate.DelegateTask;
import org.flowable.task.service.delegate.TaskListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.entity.SysExposeRequest;
import vn.com.fecredit.flowable.exposer.repository.SysExposeRequestRepository;

@Component("caseLifecycleListener")
public class CaseLifecycleListener implements TaskListener {

    @Autowired
    private SysExposeRequestRepository requestRepo;

    @Override
    public void notify(DelegateTask delegateTask) {
        try {
            String caseInstanceId = delegateTask.getProcessInstanceId();
            if (caseInstanceId == null) return;

            // infer entity type from process definition key (best-effort) or leave null
            String processDefinitionKey = delegateTask.getProcessDefinitionId();
            String entityType = null;
            if (processDefinitionKey != null && processDefinitionKey.contains(":")) {
                // processDefinitionId format: key:version:id — try to extract key
                entityType = processDefinitionKey.split(":")[0];
            }

            SysExposeRequest req = new SysExposeRequest();
            req.setCaseInstanceId(caseInstanceId);
            req.setEntityType(entityType);
            req.setRequestedBy(delegateTask.getAssignee());
            requestRepo.save(req);
        } catch (Exception ex) {
            // don't fail the task on listener error; log if logging available
            System.err.println("CaseLifecycleListener failed: " + ex.getMessage());
        }
    }
}
