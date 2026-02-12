package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.Map;

@SpringBootTest
public class MetadataInspectTest {

    @Autowired
    private MetadataResolver resolver;

    @Test
    public void inspectOrderMetadata() {
        try {
            MetadataDefinition md = resolver.resolveForClass("Order");
            System.out.println("resolveForClass(Order) -> " + (md == null ? "<null>" : md._class + " entityType=" + md.entityType + " fields=" + (md.fields==null?0:md.fields.size())));
            Map<String, vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition.FieldMapping> mappings = resolver.mappingsMetadataFor("Order");
            System.out.println("mappingsMetadataFor(Order) -> size=" + (mappings == null ? 0 : mappings.size()));
            if (mappings != null) {
                mappings.forEach((k,v) -> System.out.println("mapping: " + k + " -> jsonPath=" + v.jsonPath + " klass=" + v.klass + " plainColumn=" + v.plainColumn));
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
