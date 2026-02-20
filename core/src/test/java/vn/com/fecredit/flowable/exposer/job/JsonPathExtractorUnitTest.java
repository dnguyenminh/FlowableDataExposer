package vn.com.fecredit.flowable.exposer.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonPathExtractorUnitTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void extractor_expands_array_root_into_multiple_rows() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/metadata/indices/sample-index-item.json")) {
            String txt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            IndexDefinition def = om.readValue(txt, IndexDefinition.class);

            String payload = "{\"items\":[{\"id\":\"I1\",\"sku\":\"S1\"},{\"id\":\"I2\",\"sku\":\"S2\"}], \"businessKey\":\"BK1\"}";

            CaseDataWorker worker = new CaseDataWorker();
            java.lang.reflect.Field omField = CaseDataWorker.class.getDeclaredField("om");
            omField.setAccessible(true);
            omField.set(worker, om);

            // Extract items using JsonPath and invoke private buildIndexRow for each
            Object extracted = JsonPath.read(payload, "$.items");
            assertThat(extracted).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) extracted;

            Method build = CaseDataWorker.class.getDeclaredMethod("buildIndexRow", IndexDefinition.class, String.class, String.class);
            build.setAccessible(true);

            java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (Object item : items) {
                String jsonForItem = om.writeValueAsString(item);
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) build.invoke(worker, def, "case-1", jsonForItem);
                rows.add(row);
            }

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get("case_instance_id")).isEqualTo("case-1");
            assertThat(rows.get(0).get("id")).isEqualTo("I1");
            assertThat(rows.get(1).get("id")).isEqualTo("I2");
        }
    }
}
