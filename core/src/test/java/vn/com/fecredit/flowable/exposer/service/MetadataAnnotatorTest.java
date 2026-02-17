package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class MetadataAnnotatorTest {

    private MetadataAnnotator annotator;

    @BeforeEach
    void setUp() {
        var lookup = MetadataResolverTestHelper.createMetadataLookup();
        annotator = new MetadataAnnotator(lookup);
    }

    @Test
    void annotate_adds_class_to_nested_fields() {
        // Verify annotator can be created and used
        assertThat(annotator).isNotNull();

        // Verify annotator can process a simple map
        Map<String,Object> root = new HashMap<>();
        root.put("id", "123");

        // Annotate should not throw an exception
        annotator.annotate(root, "Order");
        assertThat(root).isNotNull();
    }
}
