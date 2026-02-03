package vn.com.fecredit.flowable.exposer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "vn.com.fecredit.flowable.exposer")
@EnableScheduling
public class FlowableExposerApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlowableExposerApplication.class, args);
    }
}
