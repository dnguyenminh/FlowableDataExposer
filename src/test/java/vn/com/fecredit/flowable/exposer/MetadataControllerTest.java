package vn.com.fecredit.flowable.exposer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class MetadataControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.ComponentScan(basePackages = "vn.com.fecredit.flowable.exposer")
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    static class TestConfig {
        // test-only configuration root so SpringBootTest can detect component-scan for repo beans
    }

    @Test
    void validate_and_fieldCheck_endpoints_work() throws Exception {
        String payload = "{\n  \"class\": \"Order\",\n  \"entityType\": \"Order\",\n  \"mappings\": [{ \"column\": \"total_amount\", \"jsonPath\": \"$.total\" }]\n}";
        mvc.perform(post("/api/metadata/validate").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        String sample = "{ \"class\": \"Order\", \"sampleBlob\": { \"total\": 12.5 } }";
        mvc.perform(post("/api/metadata/field-check").contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extracted.total_amount").value(12.5));
    }

    @Test
    void apply_endpoint_persists_metadata_to_db() throws Exception {
        String payload = "{\n  \"class\": \"OrderX\",\n  \"entityType\": \"OrderX\",\n  \"mappings\": [{ \"column\": \"total_amount\", \"jsonPath\": \"$.total\", \"exportToPlain\": true, \"plainColumn\": \"order_total\" }]\n}";
        mvc.perform(post("/api/metadata/apply").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value("OrderX"));
    }
}
