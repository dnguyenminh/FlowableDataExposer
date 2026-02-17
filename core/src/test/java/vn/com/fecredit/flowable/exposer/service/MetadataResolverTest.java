package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
public class MetadataResolverTest {

    private MetadataResolver resolver;
    private SysExposeClassDefRepository mockRepository;
    private MetadataResourceLoader mockResourceLoader;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockRepository = Mockito.mock(SysExposeClassDefRepository.class);
        mockResourceLoader = Mockito.mock(MetadataResourceLoader.class);
        resolver = new MetadataResolver(mockRepository, mockResourceLoader);
    }

    @Test
    void resolveForClassWithNullReturnsNull() {
        var result = resolver.resolveForClass(null);
        assertThat(result).isNull();
    }

    @Test
    void resolveForClassWithEmptyStringReturnsNull() {
        var result = resolver.resolveForClass("");
        assertThat(result).isNull();
    }

    @Test
    void resolveMappingsForNonExistentClassReturnsEmptyMap() {
        Mockito.when(mockResourceLoader.getByClass("NonExistentClass"))
                .thenReturn(Optional.empty());

        var mappings = resolver.mappingsMetadataFor("NonExistentClass");
        assertThat(mappings).isEmpty();
    }

    @Test
    void resolveForClassReturnsMetadataWhenFileMetadataExists() {
        MetadataDefinition orderDef = new MetadataDefinition();
        orderDef._class = "Order";
        orderDef.entityType = "Order";
        orderDef.tableName = "case_plain_order";
        orderDef.version = 1;

        Mockito.when(mockResourceLoader.getByClass("Order"))
                .thenReturn(Optional.of(orderDef));

        var result = resolver.resolveForClass("Order");
        assertThat(result).isNotNull();
        assertThat(result._class).isEqualTo("Order");
        assertThat(result.entityType).isEqualTo("Order");
        assertThat(result.tableName).isEqualTo("case_plain_order");
    }

    @Test
    void mappingsMetadataForReturnsMappingsWhenPresent() {
        MetadataDefinition orderDef = new MetadataDefinition();
        orderDef._class = "Order";
        orderDef.entityType = "Order";

        var mapping = new MetadataDefinition.FieldMapping();
        mapping.column = "order_total";
        mapping.jsonPath = "$.total";

        orderDef.mappings = java.util.Collections.singletonList(mapping);

        Mockito.when(mockResourceLoader.getByClass("Order"))
                .thenReturn(Optional.of(orderDef));

        var mappings = resolver.mappingsMetadataFor("Order");
        assertThat(mappings).isNotNull();
        assertThat(mappings).containsKey("order_total");
        assertThat(mappings.get("order_total").jsonPath).isEqualTo("$.total");
    }

    @Test
    void resolveForClassPrefersDatabaseMetadataOverFile() throws Exception {
        var dbEntity = new vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef();
        dbEntity.setClassName("Order");
        dbEntity.setVersion(2);
        dbEntity.setEnabled(true);

        MetadataDefinition dbDef = new MetadataDefinition();
        dbDef._class = "Order";
        dbDef.version = 2;
        dbEntity.setJsonDefinition(mapper.writeValueAsString(dbDef));

        Mockito.when(mockRepository.findLatestEnabledByEntityType("Order"))
                .thenReturn(Optional.of(dbEntity));

        var result = resolver.resolveForClass("Order");
        assertThat(result).isNotNull();
        assertThat(result.version).isEqualTo(2);
    }
}
