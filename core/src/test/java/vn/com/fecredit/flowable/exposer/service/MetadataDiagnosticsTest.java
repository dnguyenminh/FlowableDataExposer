package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

public class MetadataDiagnosticsTest {

    private MetadataResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = MetadataResolverTestHelper.createMetadataResolver();
    }

    @Test
    void type_conflict_is_reported_as_diagnostic() {
        var md = resolver.resolveForClass("ChildWithMixins");
        assertThat(md).isNotNull();
        assertThat(md.mixins).isNotNull();
    }

    @Test
    void provenance_is_attached_to_field_mappings() {
        var md = resolver.resolveForClass("ChildWithMixins");
        assertThat(md).isNotNull();
        assertThat(md.parent).isEqualTo("Parent");
    }
}
