package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

public class ExposeMappingValidationTest {

    @Test
    void sample_expose_matches_expose_mapping_schema_expectations() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("metadata/exposes/sample-expose.json")) {
            assertThat(is).withFailMessage("sample-expose.json must exist in test resources").isNotNull();
            JsonNode root = mapper.readTree(is);

            // schema pointer should reference expose-mapping-schema
            assertThat(root.path("$schema").asText()).contains("expose-mapping-schema.json");

            // must have either 'class' or 'workClassReference'
            boolean hasClass = root.has("class");
            boolean hasRef = root.has("workClassReference");
            assertThat(hasClass || hasRef).withFailMessage("Expose mapping must have 'class' or 'workClassReference'").isTrue();

            // root should have jsonPath
            assertThat(root.has("jsonPath")).withFailMessage("Expose mapping should have 'jsonPath' at root").isTrue();

            // mappings array: each item must include jsonPath
            assertThat(root.has("mappings")).withFailMessage("Expose mapping must declare 'mappings' array").isTrue();
            for (JsonNode item : root.get("mappings")) {
                assertThat(item.has("jsonPath")).withFailMessage(() -> "Mapping item missing jsonPath: " + item.toString()).isTrue();
            }
        }
    }
}
