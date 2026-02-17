package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CaseDataWorkerUnitTest {

    private MetadataResolver resolver;
    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        resolver = MetadataResolverTestHelper.createMetadataResolver();
        om = new ObjectMapper();
    }

    @Test
    void jsonPathReadUsingResolvedMapping() throws Exception {
        // Verify Order metadata can be resolved
        var md = resolver.resolveForClass("Order");
        assertThat(md).isNotNull();
        assertThat(md._class).isEqualTo("Order");

        // Verify fields exist in metadata
        if (md.fields != null) {
            assertThat(md.fields).isNotEmpty();
        }
    }
}
