package vn.com.fecredit.flowable.exposer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "vn.com.fecredit.flowable.exposer")
@EnableScheduling
public class ComplexSampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(ComplexSampleApplication.class, args);
    }
}
