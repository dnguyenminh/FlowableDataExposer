package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

public class MetadataResolverIndexMapAccessTest {

    private MetadataResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = MetadataResolverTestHelper.createMetadataResolver();
    }

    @Test
    void array_and_paren_and_map_access_joining() {
        var md = resolver.resolveForClass("OrderArray");
        assertThat(md).isNotNull();
        assertThat(md._class).isEqualTo("OrderArray");
    }
}
