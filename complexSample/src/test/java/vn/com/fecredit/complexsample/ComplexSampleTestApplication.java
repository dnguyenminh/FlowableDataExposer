package vn.com.fecredit.complexsample;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

// Test-only application that uses default auto-configuration but restricts component scanning
// to the complexSample package to reduce risk of picking up other modules' Flowable starters.
@SpringBootApplication
@ComponentScan(basePackages = {"vn.com.fecredit.complexsample"})
public class ComplexSampleTestApplication {
}
