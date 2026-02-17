package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import vn.com.fecredit.flowable.exposer.entity.SysCaseDataStore;
import static org.assertj.core.api.Assertions.assertThat;

public class SysCaseDataStoreTest {
    @Test
    void entity_has_default_constructor_and_fields() throws Exception {
        SysCaseDataStore s = new SysCaseDataStore();
        s.setEntityType("Order");
        s.setStatus("ACTIVE");
        assertThat(s.getEntityType()).isEqualTo("Order");
        assertThat(s.getStatus()).isEqualTo("ACTIVE");
    }
}
