package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class MetadataAnnotatorTest {

    @Autowired
    MetadataAnnotator annotator;

    @Autowired
    MetadataResolver resolver;

    @Test
    void annotate_adds_class_to_nested_fields() {
        // prepare a simple metadata def in resolver cache via file-backed metadata (Order.json exists)
        Map<String,Object> root = new HashMap<>();
        Map<String,Object> meta = new HashMap<>();
        meta.put("priority", "HIGH");
        root.put("meta", meta);

        // run annotator using entity type 'Order' (Order.json defines fields meta->Meta)
        annotator.annotate(root, "Order");

        assertThat(root.get("meta")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked") Map<String,Object> mm = (Map<String,Object>) root.get("meta");
        assertThat(mm).containsKey("@class");
        assertThat(mm.get("@class")).isEqualTo("Meta");
    }
}
