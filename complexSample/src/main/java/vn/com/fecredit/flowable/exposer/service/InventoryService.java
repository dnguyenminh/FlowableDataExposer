package vn.com.fecredit.flowable.exposer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InventoryService {
    private final Logger log = LoggerFactory.getLogger(InventoryService.class);

    public boolean check() {
        log.debug("InventoryService.check() invoked - returning true (stub)");
        return true;
    }
}
