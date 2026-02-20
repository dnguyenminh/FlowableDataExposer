package vn.com.fecredit.flowable.exposer.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CaseDataWorkerIndexUnitTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void buildIndexRow_extracts_expected_columns_from_order_metadata() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/metadata/indices/sample-index-order.json")) {
            String txt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            IndexDefinition def = om.readValue(txt, IndexDefinition.class);

            // sample JSON payload (order) taken from test resources classes/Order.json
            // Use a real order payload (not the metadata file) for extraction
            String payload = "{\"total\":1234.56, \"businessKey\":\"BK1\", \"id\":\"ORD-1\"}";

            // create worker instance via default constructor
            CaseDataWorker worker = new CaseDataWorker();
            // inject ObjectMapper into private autowired field so buildIndexRow can use it
            java.lang.reflect.Field omField = CaseDataWorker.class.getDeclaredField("om");
            omField.setAccessible(true);
            omField.set(worker, om);

            // use reflection to access private buildIndexRow method
            Method m = CaseDataWorker.class.getDeclaredMethod("buildIndexRow", IndexDefinition.class, String.class, String.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) m.invoke(worker, def, "case-123", payload);

            assertThat(row).isNotNull();
            assertThat(row.get("case_instance_id")).isEqualTo("case-123");
            // mappings in sample-index-order.json include total and businessKey
            assertThat(row).containsKey("total_amount");
            assertThat(row).containsKey("business_key");
        }
    }
}
