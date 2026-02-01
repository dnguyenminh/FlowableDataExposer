package vn.com.fecredit.flowable.exposer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest(vn.com.fecredit.flowable.exposer.web.MetadataController.class)
@AutoConfigureMockMvc
public class MetadataControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @org.springframework.boot.test.mock.mockito.MockBean
    vn.com.fecredit.flowable.exposer.service.MetadataResolver metadataResolver;

    @org.springframework.boot.test.mock.mockito.MockBean
    vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository sysExposeClassDefRepository;

    @Test
    void validate_and_fieldCheck_endpoints_work() throws Exception {
        // mock resolver to behave like the file-backed metadata used by full-context tests
        org.mockito.Mockito.when(metadataResolver.mappingsFor("Order")).thenReturn(java.util.Map.of("total_amount", "$.total"));
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
        org.mockito.Mockito.when(sysExposeClassDefRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i->i.getArgument(0));
        String payload = "{\n  \"class\": \"OrderX\",\n  \"entityType\": \"OrderX\",\n  \"mappings\": [{ \"column\": \"total_amount\", \"jsonPath\": \"$.total\", \"exportToPlain\": true, \"plainColumn\": \"order_total\" }]\n}";
        mvc.perform(post("/api/metadata/apply").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value("OrderX"));
    }
}
