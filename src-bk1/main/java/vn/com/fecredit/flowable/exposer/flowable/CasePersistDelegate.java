package vn.com.fecredit.flowable.exposer.flowable;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.service.CaseDataWorker;
import vn.com.fecredit.flowable.exposer.service.ExposeInterceptor;

import java.util.Map;

@Component("casePersistDelegate")
public class CasePersistDelegate implements JavaDelegate {

    private final ExposeInterceptor exposeInterceptor;
    private final CaseDataWorker caseDataWorker;

    public CasePersistDelegate(ExposeInterceptor exposeInterceptor, CaseDataWorker caseDataWorker) {
        this.exposeInterceptor = exposeInterceptor;
        this.caseDataWorker = caseDataWorker;
    }

    @Override
    public void execute(DelegateExecution execution) {
        try {
            String processInstanceId = execution.getProcessInstanceId();
            Map<String, Object> vars = execution.getVariables();
            var saved = exposeInterceptor.persistCase(processInstanceId, "Order", vars);
            // process immediately (synchronous) to prove indexing works
            caseDataWorker.process(saved);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
