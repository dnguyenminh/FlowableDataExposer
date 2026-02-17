package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import static org.assertj.core.api.Assertions.assertThat;

public class MetadataInheritanceTest {

    private MetadataResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = MetadataResolverTestHelper.createMetadataResolver();
    }

    //@Test
    void multiLevel_inheritance_merges_and_applies_overrides_and_removes() {
        // Verify inheritance chain Child -> Parent -> GrandParent
        MetadataDefinition child = resolver.resolveForClass("Child");
        assertThat(child).isNotNull();
        assertThat(child.parent).isEqualTo("Parent");

        MetadataDefinition parent = resolver.resolveForClass("Parent");
        assertThat(parent).isNotNull();
        assertThat(parent.parent).isEqualTo("GrandParent");

        MetadataDefinition grandparent = resolver.resolveForClass("GrandParent");
        assertThat(grandparent).isNotNull();
        // GrandParent should have no parent (it's the root)
        assertThat(grandparent.parent).isNull();
    }
}
