package vn.com.fecredit.flowable.exposer;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef;
import vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository;
import vn.com.fecredit.flowable.exposer.service.MetadataResolver;
import vn.com.fecredit.flowable.exposer.service.MetadataResourceLoader;

import static org.junit.jupiter.api.Assertions.*;

public class MetadataDbIntegrationTest {

    @Test
    void dbBacked_metadata_overrides_file_via_repository() {
        String json = "{\n  \"class\": \"Order\",\n  \"entityType\": \"Order\",\n  \"version\": 2,\n  \"mappings\": [ { \"column\": \"total_amount\", \"jsonPath\": \"$.overridden\", \"exportToPlain\": true, \"plainColumn\": \"order_total_db\" } ]\n}";
        var repo = Mockito.mock(SysExposeClassDefRepository.class);
        var resourceLoader = Mockito.mock(MetadataResourceLoader.class);
        var ent = new SysExposeClassDef();
        ent.setClassName("Order");
        ent.setEntityType("Order");
        ent.setVersion(2);
        ent.setJsonDefinition(json);
        ent.setEnabled(true);
        Mockito.when(repo.findLatestEnabledByEntityType("Order")).thenReturn(java.util.Optional.of(ent));

        MetadataResolver resolver = new MetadataResolver(repo, resourceLoader);
        var m = resolver.mappingsMetadataFor("Order");
        assertEquals("$.overridden", m.get("total_amount").jsonPath);
        assertEquals(true, m.get("total_amount").exportToPlain);
        assertEquals("order_total_db", m.get("total_amount").plainColumn);
    }
}
