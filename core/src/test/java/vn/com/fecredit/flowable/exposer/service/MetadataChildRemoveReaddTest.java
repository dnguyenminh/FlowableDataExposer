package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class MetadataChildRemoveReaddTest {

    private MetadataResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = MetadataResolverTestHelper.createMetadataResolver();
    }

    @Test
    void child_can_remove_then_readd_column() {
        var child = resolver.resolveForClass("Child");
        assertThat(child).isNotNull();
        assertThat(child.parent).isEqualTo("Parent");
    }
}
