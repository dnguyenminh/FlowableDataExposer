package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class MetadataResolverIndexMapAccessTest {

    @Autowired
    MetadataResolver resolver;

    @Test
    void array_and_paren_and_map_access_joining() {
        Map<String, MetadataDefinition.FieldMapping> mappings = resolver.mappingsMetadataFor("OrderArray");
        assertThat(mappings).isNotNull();

        assertThat(mappings).containsKey("item_1_id");
        assertThat(mappings.get("item_1_id").jsonPath).isEqualTo("$.items[0].id");

        assertThat(mappings).containsKey("item_1_id_paren");
        assertThat(mappings.get("item_1_id_paren").jsonPath).isEqualTo("$.items(0).id");

        assertThat(mappings).containsKey("item_named_id");
        assertThat(mappings.get("item_named_id").jsonPath).isEqualTo("$.items['sku-123'].id");
    }
}
