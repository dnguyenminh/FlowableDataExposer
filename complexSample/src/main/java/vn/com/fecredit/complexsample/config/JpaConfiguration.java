package vn.com.fecredit.complexsample.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Explicit JPA configuration to ensure entities and repositories
 * from the core module are properly scanned.
 */
@Configuration
@EntityScan(basePackages = {
        "vn.com.fecredit.flowable.exposer.entity",
        "vn.com.fecredit.complexsample.entity"
})
@EnableJpaRepositories(basePackages = {
        "vn.com.fecredit.flowable.exposer.repository",
        "vn.com.fecredit.complexsample.repository"
})
public class JpaConfiguration {
}

