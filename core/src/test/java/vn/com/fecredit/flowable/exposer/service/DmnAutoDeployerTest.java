package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class DmnAutoDeployerTest {
    @Test
    void class_exists() throws Exception {
        Class<?> cls = Class.forName("vn.com.fecredit.flowable.exposer.config.DmnAutoDeployer");
        assertThat(cls).isNotNull();
    }
}
