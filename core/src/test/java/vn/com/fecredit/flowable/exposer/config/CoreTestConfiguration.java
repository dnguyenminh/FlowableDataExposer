package vn.com.fecredit.flowable.exposer.config;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Test configuration for Spring Boot tests in the core module.
 * This provides the @SpringBootConfiguration that tests are looking for.
 *
 * Scans only complexsample packages to provide real implementations while
 * tests can use the shims from flowable.exposer package.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
    // Exclude web-related auto-configurations that might cause conflicts
    WebMvcAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class
})
@ComponentScan(basePackages = {
    "vn.com.fecredit.flowable.exposer"
}, excludeFilters = {
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.web\\..*"),
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.config\\..*")
})
@EnableJpaRepositories(basePackages = {
    "vn.com.fecredit.flowable.exposer.repository"
})
@EntityScan(basePackages = {
    "vn.com.fecredit.complexsample.entity"
})
public class CoreTestConfiguration {
    // Test configuration class
}
