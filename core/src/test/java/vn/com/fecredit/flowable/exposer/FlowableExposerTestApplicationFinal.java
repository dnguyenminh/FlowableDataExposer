package vn.com.fecredit.flowable.exposer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Test application configuration for core module integration tests.
 * This application class provides full Spring Boot context with:
 * - JPA repository auto-proxying
 * - Component scanning across the flowable exposer package
 * - Scheduled task support
 * - Entity scanning for all JPA entities
 */
@SpringBootApplication
@EnableScheduling
@EntityScan(basePackages = {
    "vn.com.fecredit.flowable.exposer.entity",
    "vn.com.fecredit.flowable.exposer.config"
})
@ComponentScan(basePackages = {
    "vn.com.fecredit.flowable.exposer",
    "vn.com.fecredit.simplesample"
}, excludeFilters = {
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.config\\.CoreTestConfiguration")
})
@EnableJpaRepositories(basePackages = {
    "vn.com.fecredit.flowable.exposer.repository"
})
public class FlowableExposerTestApplicationFinal {

    public static void main(String[] args) {
        SpringApplication.run(FlowableExposerTestApplicationFinal.class, args);
    }
}

