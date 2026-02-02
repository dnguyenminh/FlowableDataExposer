package vn.com.fecredit.flowable.exposer.service.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies JSON <-> POJO mapping for {@link MetadataDefinition} and its
 * nested types. Guards against accidental renames that would break the
 * metadata UI or resolver.
 */
public class MetadataDefinitionTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void jsonDeserialization_mapsClass_andFieldMappingHints() throws Exception {
        String json = "{\n" +
                "  \"class\": \"Order\",\n" +
                "  \"entityType\": \"Order\",\n" +
                "  \"mappings\": [ { \"column\": \"total_amount\", \"jsonPath\": \"$.total\", \"exportToPlain\": true, \"plainColumn\": \"order_total\" } ],\n" +
                "  \"fields\": [ { \"name\": \"customer\", \"className\": \"Customer\" } ]\n" +
                "}";

        MetadataDefinition md = om.readValue(json, MetadataDefinition.class);
        assertThat(md._class).isEqualTo("Order");
        assertThat(md.entityType).isEqualTo("Order");
        assertThat(md.mappings).hasSize(1);
        MetadataDefinition.FieldMapping fm = md.mappings.get(0);
        assertThat(fm.column).isEqualTo("total_amount");
        assertThat(fm.jsonPath).isEqualTo("$.total");
        assertThat(fm.exportToPlain).isTrue();
        assertThat(fm.plainColumn).isEqualTo("order_total");
        assertThat(md.fields).hasSize(1);
        assertThat(md.fields.get(0).name).isEqualTo("customer");
        assertThat(md.fields.get(0).className).isEqualTo("Customer");
    }
}
