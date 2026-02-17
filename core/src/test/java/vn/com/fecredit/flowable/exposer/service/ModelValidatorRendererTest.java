package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class ModelValidatorRendererTest {
    @Test
    void load_class() throws Exception {
        Class<?> cls = Class.forName("vn.com.fecredit.flowable.exposer.util.ModelValidatorRenderer");
        assertThat(cls).isNotNull();
    }
}
