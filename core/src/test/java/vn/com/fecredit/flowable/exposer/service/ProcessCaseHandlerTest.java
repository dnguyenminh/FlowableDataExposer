package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class ProcessCaseHandlerTest {
    @Test
    void class_exists() throws Exception {
        Class<?> cls = Class.forName("vn.com.fecredit.flowable.exposer.flowable.ProcessCaseHandler");
        assertThat(cls).isNotNull();
    }
}
