package vn.com.fecredit.flowable.exposer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "vn.com.fecredit.flowable.exposer")
public class FlowableExposerApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlowableExposerApplication.class, args);
    }
}
