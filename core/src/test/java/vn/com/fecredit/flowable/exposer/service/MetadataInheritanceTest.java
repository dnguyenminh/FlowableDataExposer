package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class MetadataInheritanceTest {

    @Autowired
    MetadataResolver resolver;

    @Test
    void multiLevel_inheritance_merges_and_applies_overrides_and_removes() {
        // Child -> Parent -> GrandParent
        MetadataDefinition childDef = resolver.resolveForClass("Child");
        assertThat(childDef).isNotNull();
        assertThat(childDef.parent).isEqualTo("Parent");

        Map<String, MetadataDefinition.FieldMapping> merged = resolver.mappingsMetadataFor("Child");
        // a_col was removed by Child
        assertThat(merged).doesNotContainKey("a_col");
        // b_col comes from GrandParent but overridden by Parent
        assertThat(merged).containsKey("b_col");
        assertThat(merged.get("b_col").jsonPath).isEqualTo("$.b_override");
        // c_col overridden by Child
        assertThat(merged).containsKey("c_col");
        assertThat(merged.get("c_col").jsonPath).isEqualTo("$.c_child");
        // d_col defined on Child
        assertThat(merged).containsKey("d_col");
    }
}
