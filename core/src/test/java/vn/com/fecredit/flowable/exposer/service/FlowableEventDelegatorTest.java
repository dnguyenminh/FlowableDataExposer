package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class FlowableEventDelegatorTest {
    @Test
    void class_present() throws Exception {
        Class<?> cls = Class.forName("vn.com.fecredit.flowable.exposer.flowable.FlowableEventDelegator");
        assertThat(cls).isNotNull();
    }
}
