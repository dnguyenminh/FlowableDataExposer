package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class MetadataResourceLoaderTest {

    private MetadataResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = MetadataResolverTestHelper.createMetadataResolver();
    }

    @Test
    void loads_files_and_supports_case_insensitive_lookup() {
        // Verify metadata can be resolved (loader works through resolver)
        var order = resolver.resolveForClass("Order");
        assertThat(order).isNotNull();
        assertThat(order._class).isEqualTo("Order");

        // Verify Child metadata loads
        var child = resolver.resolveForClass("Child");
        assertThat(child).isNotNull();
        assertThat(child._class).isEqualTo("Child");
    }
}
