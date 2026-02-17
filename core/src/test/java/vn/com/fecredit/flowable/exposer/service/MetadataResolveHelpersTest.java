package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class MetadataResolveHelpersTest {

    @Test
    void cloneMappingWithProvenance_sets_provenance_and_copies_fields() {
        MetadataDefinition.FieldMapping fm = new MetadataDefinition.FieldMapping();
        fm.column = "col1";
        fm.jsonPath = "$.a";
        fm.type = "string";
        fm.nullable = true;
        fm.defaultValue = "def";
        fm.index = Boolean.TRUE;
        fm.order = 2;
        fm.remove = false;
        fm.exportToPlain = true;
        fm.plainColumn = "plain_col";
        fm.exportDest = List.of("dest");
        fm.sensitive = true;
        fm.piiMask = "mask";
        fm.klass = "SomeClass";
        fm.arrayIndex = Integer.valueOf(3);

        MetadataDefinition src = new MetadataDefinition();
        src._class = "MyClass";
        src.migratedToModule = null;

        MetadataDefinition.FieldMapping fm2 = MetadataResolveHelpers.cloneMappingWithProvenance(fm, src, "file");

        assertThat(fm2).isNotNull();
        assertThat(fm2.column).isEqualTo(fm.column);
        assertThat(fm2.jsonPath).isEqualTo(fm.jsonPath);
        assertThat(fm2.type).isEqualTo(fm.type);
        assertThat(fm2.nullable).isEqualTo(fm.nullable);
        assertThat(fm2.defaultValue).isEqualTo(fm.defaultValue);
        assertThat(fm2.index).isEqualTo(fm.index);
        assertThat(fm2.order).isEqualTo(fm.order);
        assertThat(fm2.remove).isEqualTo(fm.remove);
        assertThat(fm2.exportToPlain).isEqualTo(fm.exportToPlain);
        assertThat(fm2.plainColumn).isEqualTo(fm.plainColumn);
        assertThat(fm2.exportDest).isEqualTo(fm.exportDest);
        assertThat(fm2.sensitive).isEqualTo(fm.sensitive);
        assertThat(fm2.piiMask).isEqualTo(fm.piiMask);
        assertThat(fm2.klass).isEqualTo(fm.klass);
        assertThat(fm2.arrayIndex).isEqualTo(fm.arrayIndex);

        // provenance fields
        assertThat(fm2.sourceClass).isEqualTo("MyClass");
        assertThat(fm2.sourceKind).isEqualTo("file");
        assertThat(fm2.sourceModule).isEqualTo("core");
        assertThat(fm2.sourceLocation).contains("MyClass");
    }

    @Test
    void checkTypeConflict_reports_conflict_for_same_plainColumn_different_types() {
        Map<String, MetadataDefinition.FieldMapping> merged = new HashMap<>();
        MetadataDefinition.FieldMapping existing = new MetadataDefinition.FieldMapping();
        existing.plainColumn = "pA";
        existing.type = "string";
        existing.sourceClass = "Existing";
        merged.put("existingCol", existing);

        MetadataDefinition.FieldMapping candidate = new MetadataDefinition.FieldMapping();
        candidate.plainColumn = "pA";
        candidate.type = "number";
        candidate.sourceClass = "Candidate";

        Map<String, List<String>> diagnostics = new HashMap<>();
        MetadataResolveHelpers.checkTypeConflict("TargetClass", merged, candidate, diagnostics);

        assertThat(diagnostics).containsKey("TargetClass");
        assertThat(diagnostics.get("TargetClass").get(0)).contains("type conflict for plainColumn");

        // when types match there should be no diagnostics
        diagnostics.clear();
        candidate.type = "string";
        MetadataResolveHelpers.checkTypeConflict("TargetClass", merged, candidate, diagnostics);
        assertThat(diagnostics).doesNotContainKey("TargetClass");
    }
}
