package vn.com.fecredit.flowable.exposer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Test configuration for core module tests.
 * Enables full Spring Boot context with JPA repositories and component scanning.
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = "vn.com.fecredit.flowable.exposer")
@EnableJpaRepositories(basePackages = "vn.com.fecredit.flowable.exposer.repository")
public class FlowableExposerTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlowableExposerTestApplication.class, args);
    }
}

