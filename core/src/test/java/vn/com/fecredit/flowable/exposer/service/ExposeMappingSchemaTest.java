package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

public class ExposeMappingSchemaTest {

    @Test
    void sample_expose_conforms_to_basic_schema_requirements() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("metadata/exposes/sample-expose.json")) {
            assertThat(is).withFailMessage("sample-expose.json must exist in test resources").isNotNull();
            JsonNode root = mapper.readTree(is);

            // schema updated: 'class' is optional; require 'workClassReference' to be present
            boolean hasRef = root.has("workClassReference");
            assertThat(hasRef).withFailMessage("Expose mapping must have 'workClassReference'").isTrue();

            // optional: jsonPath at root
            assertThat(root.has("jsonPath")).withFailMessage("Expose mapping should have 'jsonPath' at root").isTrue();

            // mappings array: each item must include jsonPath
            if (root.has("mappings") && root.get("mappings").isArray()) {
                for (JsonNode item : root.get("mappings")) {
                    assertThat(item.has("jsonPath")).withFailMessage(() -> "Mapping item missing jsonPath: " + item.toString()).isTrue();
                }
            }
        }
    }
}
