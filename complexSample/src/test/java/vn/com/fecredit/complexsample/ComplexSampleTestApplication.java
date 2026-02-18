package vn.com.fecredit.complexsample;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flowable.FlowableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;

// Test-only application that limits auto-configuration to avoid Flowable app/idm/form engines
@SpringBootApplication
@ComponentScan(basePackages = {"vn.com.fecredit.complexsample"})
public class ComplexSampleTestApplication {
}
