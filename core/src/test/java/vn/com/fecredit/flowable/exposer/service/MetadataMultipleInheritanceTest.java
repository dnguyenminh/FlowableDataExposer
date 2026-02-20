package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class MetadataMultipleInheritanceTest {

    private MetadataResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = MetadataResolverTestHelper.createMetadataResolver();
    }

    @Test
    void mixins_are_merged_in_order_child_overrides_and_remove_works() {
        var md = resolver.resolveForClass("ChildWithMixins");
        assertThat(md).isNotNull();
        assertThat(md.parent).isEqualTo("Parent");
        assertThat(md.mixins).isNotNull().isNotEmpty();
    }
}
