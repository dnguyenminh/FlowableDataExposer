package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class MetadataResolverTest {

    @Autowired
    MetadataResolver resolver;

    @Test
    void resolveAndExpandClassScopedMappings() {
        Map<String, vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition.FieldMapping> mappings = resolver.mappingsMetadataFor("Order");
        assertThat(mappings).isNotNull();
        assertThat(mappings).containsKey("customer_id");
        var fm = mappings.get("customer_id");
        assertThat(fm.jsonPath).isEqualTo("$.customer.id");
    }
}
