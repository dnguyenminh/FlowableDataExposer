package vn.com.fecredit.flowable.exposer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.com.fecredit.flowable.exposer.entity.SysExposeRequest;
import vn.com.fecredit.flowable.exposer.repository.SysExposeRequestRepository;

@Service
public class RequestPersistService {

    private static final Logger log = LoggerFactory.getLogger(RequestPersistService.class);

    @Autowired
    private SysExposeRequestRepository requestRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createRequest(String caseInstanceId, String entityType, String requestedBy) {
        log.debug("RequestPersistService.createRequest - entering (caseInstanceId={}, entityType={}, requestedBy={})", caseInstanceId, entityType, requestedBy);
        SysExposeRequest req = new SysExposeRequest();
        req.setCaseInstanceId(caseInstanceId);
        req.setEntityType(entityType);
        req.setRequestedBy(requestedBy);
        SysExposeRequest saved = requestRepo.save(req);
        if (log.isDebugEnabled()) {
            Long id = null;
            try {
                id = saved.getId();
            } catch (Exception ignored) {
            }
            log.debug("RequestPersistService.createRequest - persisted SysExposeRequest id={} caseInstanceId={}", id, caseInstanceId);
        }
    }
}
