package vn.com.fecredit.flowable.exposer.service;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class InventoryServiceDelegate implements JavaDelegate {
    private final Logger log = LoggerFactory.getLogger(InventoryServiceDelegate.class);
    private final InventoryService inventoryService;

    public InventoryServiceDelegate(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Override
    public void execute(DelegateExecution execution) {
        boolean ok = inventoryService.check();
        log.debug("InventoryServiceDelegate setting inStock={}", ok);
        execution.setVariable("inStock", ok);
    }
}
