package vn.com.fecredit.complexsample;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vn.com.fecredit.complexsample.service.MetadataResolver;
import vn.com.fecredit.complexsample.service.metadata.MetadataDefinition;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ComplexSampleApplication.class)
public class MetadataVisibilityTest {

    @Autowired
    private MetadataResolver resolver;

    @Test
    void resolverShouldSeeCustomerMetadataFromComplexSample() {
        MetadataDefinition md = resolver.resolveForClass("Customer");
        assertThat(md).as("Customer metadata must be visible on classpath").isNotNull();
        assertThat(md._class).isEqualTo("Customer");
        assertThat(md.fields).isNotEmpty();
    }
}
