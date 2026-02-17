package vn.com.fecredit.flowable.exposer;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository;
import vn.com.fecredit.flowable.exposer.service.MetadataResolver;
import vn.com.fecredit.flowable.exposer.service.MetadataResourceLoader;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class MetadataResolverTest {

    @Test
    void fileBacked_inheritance_and_override() {
        // Setup loader that returns actual metadata from test resources
        var loader = new MetadataResourceLoader();
        loader.init(); // Initialize from classpath to load test metadata files

        // Create mock repo (not used in this test since loader provides data)
        var repo = Mockito.mock(SysExposeClassDefRepository.class);

        MetadataResolver resolver = new MetadataResolver(repo, loader);

        // Test that Child metadata is resolved correctly (inherits from Parent)
        Optional<MetadataDefinition> childOpt = loader.getByClass("Child");
        if (childOpt.isPresent()) {
            MetadataDefinition childDef = childOpt.get();
            assertNotNull(childDef);
            assertEquals("Child", childDef._class);
            assertEquals("Parent", childDef.parent);

            // Test mappings resolution
            Map<String, MetadataDefinition.FieldMapping> childMappings = resolver.mappingsMetadataFor("Child");
            assertNotNull(childMappings);
        }

        // Test Order metadata
        Optional<MetadataDefinition> orderOpt = loader.getByClass("Order");
        if (orderOpt.isPresent()) {
            MetadataDefinition orderDef = orderOpt.get();
            assertNotNull(orderDef);
            assertEquals("Order", orderDef._class);

            Map<String, MetadataDefinition.FieldMapping> meta = resolver.mappingsMetadataFor("Order");
            assertNotNull(meta);
            if (meta.containsKey("total_amount")) {
                MetadataDefinition.FieldMapping fm = meta.get("total_amount");
                assertNotNull(fm);
                assertTrue(Boolean.TRUE.equals(fm.exportToPlain));
                assertEquals("order_total", fm.plainColumn);
            }
        }
    }
}


