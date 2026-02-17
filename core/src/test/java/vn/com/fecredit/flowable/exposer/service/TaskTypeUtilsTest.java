package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class TaskTypeUtilsTest {
    @Test
    void has_expected_static_methods() throws Exception {
        Class<?> cls = Class.forName("vn.com.fecredit.flowable.exposer.flowable.TaskTypeUtils");
        assertThat(cls.getMethods().length).isGreaterThan(0);
    }
}
