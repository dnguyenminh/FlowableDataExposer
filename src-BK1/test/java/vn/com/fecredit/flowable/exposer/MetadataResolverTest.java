package vn.com.fecredit.flowable.exposer;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository;
import vn.com.fecredit.flowable.exposer.service.MetadataResolver;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MetadataResolverTest {

    @Test
    void fileBacked_inheritance_and_override() {
        var repo = Mockito.mock(SysExposeClassDefRepository.class);
        MetadataResolver resolver = new MetadataResolver(repo);
        Map<String, String> child = resolver.mappingsFor("Child");
        assertEquals("$.a", child.get("a"));
        // child overrides b
        assertEquals("$.b_override", child.get("b"));
        assertEquals("$.c", child.get("c"));

        var meta = resolver.mappingsMetadataFor("Order");
        MetadataDefinition.FieldMapping fm = meta.get("total_amount");
        assertNotNull(fm);
        assertTrue(Boolean.TRUE.equals(fm.exportToPlain));
        assertEquals("order_total", fm.plainColumn);
    }
}
