package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class MetadataMultipleInheritanceTest {

    @Autowired
    MetadataResolver resolver;

    @Test
    void mixins_are_merged_in_order_child_overrides_and_remove_works() {
        MetadataDefinition md = resolver.resolveForClass("ChildWithMixins");
        assertThat(md).isNotNull();
        assertThat(md.parent).isEqualTo("Parent");

        Map<String, MetadataDefinition.FieldMapping> merged = resolver.mappingsMetadataFor("ChildWithMixins");

        // parent contribution still present
        assertThat(merged).containsKey("b_col");
        assertThat(merged.get("b_col").jsonPath).isEqualTo("$.b_override");

        // mixin fields present
        assertThat(merged).containsKey("b1_col");
        assertThat(merged.get("b1_col").jsonPath).isEqualTo("$.b1");

        // a1_col was provided by MixinA but removed by child
        assertThat(merged).doesNotContainKey("a1_col");

        // shared_col precedence: MixinA -> MixinB -> ChildWithMixins (child overrides both)
        assertThat(merged).containsKey("shared_col");
        assertThat(merged.get("shared_col").jsonPath).isEqualTo("$.sharedChild");

        // child's own column
        assertThat(merged).containsKey("child_col");
    }
}
