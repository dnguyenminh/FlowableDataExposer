package vn.com.fecredit.flowable.exposer.config;

import org.flowable.dmn.api.DmnRepositoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class DmnAutoDeployer {
    private final Logger log = LoggerFactory.getLogger(DmnAutoDeployer.class);
    private final DmnRepositoryService dmnRepositoryService;

    public DmnAutoDeployer(DmnRepositoryService dmnRepositoryService) {
        this.dmnRepositoryService = dmnRepositoryService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void deployDecisions() {
        try {
            ClassPathResource r = new ClassPathResource("decisions/order-discount-rules.dmn");
            if (!r.exists()) {
                log.debug("No DMN resource found at decisions/order-discount-rules.dmn");
                return;
            }
            try (InputStream in = r.getInputStream()) {
                // Deploy DMN; createDeployment will create a new deployment each run in dev.
                dmnRepositoryService.createDeployment()
                        .addInputStream("order-discount-rules.dmn", in)
                        .deploy();
                log.info("Deployed DMN: decisions/order-discount-rules.dmn");
            }
        } catch (Exception e) {
            log.warn("Failed to auto-deploy DMN decision", e);
        }
    }
}
