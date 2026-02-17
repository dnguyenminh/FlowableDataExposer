package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

public class MetadataDdlFromResolverTest {

    private MetadataResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = MetadataResolverTestHelper.createMetadataResolver();
    }

    @Test
    void generate_ddl_for_order_export_mappings() {
        var order = resolver.resolveForClass("Order");
        assertThat(order).isNotNull();
        assertThat(order._class).isEqualTo("Order");
    }
}