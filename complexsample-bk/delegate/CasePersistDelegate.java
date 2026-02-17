package vn.com.fecredit.complexsample.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.com.fecredit.complexsample.service.RequestPersistService;
import vn.com.fecredit.complexsample.service.MetadataAnnotator;
import vn.com.fecredit.complexsample.service.CaseDataPersistService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * BPMN JavaDelegate used by the order process to persist a case snapshot into
 * the append-only {@code sys_case_data_store} table. Delegate now calls small
 * helpers in {@link CasePersistHelpers} so the file/methods stay short.
 */
@Component("casePersistDelegate")
public class CasePersistDelegate implements JavaDelegate {

    private static final Logger logger = LoggerFactory.getLogger(CasePersistDelegate.class);

    @Autowired
    private ObjectMapper om;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MetadataAnnotator annotator;

    @Autowired
    private CaseDataPersistService persistService;

    @Autowired
    private RequestPersistService requestPersistService;

    @Override
    public void execute(DelegateExecution execution) {
        Map<String, Object> vars = CasePersistHelpers.copyVariables(execution);
        String caseInstanceId = CasePersistHelpers.resolveCaseInstanceId(execution, vars);

        // enrich and ensure class annotations
        CasePersistHelpers.populateFlowableMetadata(execution, vars);
        CasePersistHelpers.ensureClassAnnotations(vars);
        CasePersistHelpers.safeAnnotate(annotator, vars);

        logger.info("CasePersistDelegate vars before persist (caseInstanceId={}): {}", caseInstanceId, vars);

        String payload = CasePersistHelpers.stringify(om, vars);
        try {
            persistService.persistSysCaseData(caseInstanceId, "Order", payload);
            safeCreateRequest(caseInstanceId);
        } catch (Exception ex) {
            logger.warn("Failed to persist case blob for {}:", caseInstanceId, ex);
        }
    }

    private void safeCreateRequest(String caseInstanceId) {
        try {
            logger.info("CasePersistDelegate calling RequestPersistService.createRequest(caseInstanceId={}, entityType={})", caseInstanceId, "Order");
            requestPersistService.createRequest(caseInstanceId, "Order", null);
            logger.info("CasePersistDelegate created sys_expose_request (REQUIRES_NEW) for {}", caseInstanceId);
        } catch (Throwable t) {
            logger.warn("CasePersistDelegate: failed to create sys_expose_request for {}: {}", caseInstanceId, t.getMessage());
        }
    }
}
