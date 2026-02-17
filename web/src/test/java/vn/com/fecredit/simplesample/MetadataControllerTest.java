package vn.com.fecredit.simplesample;

import com.fasterxml.jackson.databind.ObjectMapper;

import vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository;
import vn.com.fecredit.flowable.exposer.service.MetadataResolver;
import vn.com.fecredit.flowable.exposer.web.MetadataController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
// import vn.com.fecredit.complexsample.repository.SysExposeClassDefRepository;
// import vn.com.fecredit.complexsample.service.MetadataResolver;
// import vn.com.fecredit.complexsample.web.MetadataController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest(MetadataController.class)
@AutoConfigureMockMvc
public class MetadataControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @org.springframework.boot.test.mock.mockito.MockBean
    MetadataResolver metadataResolver;

    @org.springframework.boot.test.mock.mockito.MockBean
    SysExposeClassDefRepository sysExposeClassDefRepository;

    @org.springframework.beans.factory.annotation.Autowired
    StubWorker caseDataWorker;

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

    // verify reindex endpoint routes to CaseDataWorker.reindexAll when present
    @org.springframework.boot.test.context.TestConfiguration
    static class Cfg {
        @org.springframework.context.annotation.Bean(name = "caseDataWorker")
        public StubWorker caseDataWorker() { return org.mockito.Mockito.spy(new StubWorker()); }
    }

    public static class StubWorker { public void reindexAll(String s) { /* spy target */ } }

    @Test
    void reindex_endpoint_triggers_worker() throws Exception {
        mvc.perform(post("/api/metadata/reindex/Order")).andExpect(status().isAccepted());
        org.mockito.Mockito.verify(caseDataWorker, org.mockito.Mockito.times(1)).reindexAll("Order");
    }
}
