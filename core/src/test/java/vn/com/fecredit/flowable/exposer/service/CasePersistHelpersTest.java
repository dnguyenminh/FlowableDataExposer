package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class CasePersistHelpersTest {
    @Test
    void class_loadable() throws Exception {
        Class<?> cls = Class.forName("vn.com.fecredit.flowable.exposer.delegate.CasePersistHelpers");
        assertThat(cls).isNotNull();
    }
}
