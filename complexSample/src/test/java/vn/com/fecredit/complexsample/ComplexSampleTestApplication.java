package vn.com.fecredit.complexsample;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

// Test application: load all Flowable auto-configurations to mirror production
@SpringBootApplication(
    scanBasePackages = {"vn.com.fecredit.complexsample"}
)
public class ComplexSampleTestApplication {

    // When running inside the IDE test runner the dispatcher servlet path bean
    // sometimes isn't auto‑registered, leading to an error during Tomcat setup
    // (see DispatcherServletPath missing exception). Providing a simple bean
    // fixes the startup and matches what Boot normally creates in a web app.
    
    @Bean
    public org.springframework.boot.autoconfigure.web.servlet.DispatcherServletPath dispatcherServletPath() {
        return () -> "/"; // simple lambda implementation
    }
}
