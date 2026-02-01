package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class CaseDataWorkerUnitTest {

    @Autowired
    MetadataResolver resolver;
    @Autowired
    ObjectMapper om;

    @Test
    void jsonPathReadUsingResolvedMapping() throws Exception {
        String payload = "{\"customer\":{\"id\":\"CUST01\"}}";
        Map<String,Object> vars = om.readValue(payload, Map.class);
        String annotated = om.writeValueAsString(vars);

        var mappings = resolver.mappingsMetadataFor("Order");
        var fm = mappings.get("customer_id");
        assertThat(fm).isNotNull();
        Object v = com.jayway.jsonpath.JsonPath.read(annotated, fm.jsonPath);
        assertThat(String.valueOf(v)).isEqualTo("CUST01");
    }
}
