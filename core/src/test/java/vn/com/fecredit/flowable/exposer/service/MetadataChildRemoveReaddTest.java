package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class MetadataChildRemoveReaddTest {

    @Autowired
    MetadataResolver resolver;

    @Test
    void child_can_remove_then_readd_column() {
        var merged = resolver.mappingsMetadataFor("Child");
        // Child fixture removes a_col and readds c_col; ensure a_col is absent and c_col present
        assertThat(merged).doesNotContainKey("a_col");
        assertThat(merged).containsKey("c_col");
    }
}
