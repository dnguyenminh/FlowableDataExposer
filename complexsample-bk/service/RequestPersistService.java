package vn.com.fecredit.complexsample.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.com.fecredit.complexsample.entity.SysExposeRequest;
import vn.com.fecredit.complexsample.repository.SysExposeRequestRepository;

@Service
public class RequestPersistService {

    private static final Logger log = LoggerFactory.getLogger(RequestPersistService.class);

    @Autowired
    private SysExposeRequestRepository requestRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createRequest(String caseInstanceId, String entityType, String requestedBy) {
        log.info("RequestPersistService.createRequest - entering (caseInstanceId={}, entityType={}, requestedBy={})", caseInstanceId, entityType, requestedBy);
        SysExposeRequest req = new SysExposeRequest();
        req.setCaseInstanceId(caseInstanceId);
        req.setEntityType(entityType);
        req.setRequestedBy(requestedBy);
        try {
            // use saveAndFlush to push the insert to the database within this REQUIRES_NEW transaction
            SysExposeRequest saved = requestRepo.saveAndFlush(req);
            Long id = null;
            try { id = saved.getId(); } catch (Exception ignored) {}
            log.info("RequestPersistService.createRequest - persisted SysExposeRequest id={} caseInstanceId={} thread={}", id, caseInstanceId, Thread.currentThread().getName());
        } catch (Exception e) {
            // Log full stacktrace and rethrow so the caller (delegate) can observe/fallback as intended
            log.error("RequestPersistService.createRequest - failed to persist SysExposeRequest for case {}: {}", caseInstanceId, e.getMessage(), e);
            throw e;
        }
    }
}
