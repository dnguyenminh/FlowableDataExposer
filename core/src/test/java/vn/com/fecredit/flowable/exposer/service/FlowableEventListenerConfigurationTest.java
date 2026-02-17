package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class FlowableEventListenerConfigurationTest {
    @Test
    void config_class_present() throws Exception {
        Class<?> cls = Class.forName("vn.com.fecredit.flowable.exposer.config.FlowableEventListenerConfiguration");
        assertThat(cls).isNotNull();
    }
}
