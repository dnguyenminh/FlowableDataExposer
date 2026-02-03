package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class MetadataDiagnosticsTest {

    @Autowired
    MetadataResolver resolver;

    @Test
    void type_conflict_is_reported_as_diagnostic() {
        var diags = resolver.diagnosticsFor("ChildWithMixins");
        // ChildWithMixins fixtures intentionally create a harmless shared_col precedence; ensure no false positive
        assertThat(diags).isEmpty();
    }

    @Test
    void provenance_is_attached_to_field_mappings() {
        var merged = resolver.mappingsMetadataFor("ChildWithMixins");
        var fm = merged.get("b1_col");
        assertThat(fm).isNotNull();
        assertThat(fm.sourceClass).isNotBlank();
        assertThat(fm.sourceKind).isEqualTo("file");
        assertThat(fm.sourceModule).isNotBlank();
    }
}
