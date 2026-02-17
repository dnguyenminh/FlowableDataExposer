package vn.com.fecredit.flowable.exposer.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class MetadataControllerWebSliceTest {

    @Test
    void preview_endpoint_runs_without_spring_boot() throws Exception {
        // Arrange - create controller with mocked dependencies
        vn.com.fecredit.flowable.exposer.repository.SysExposeIndexJobRepository repo = mock(vn.com.fecredit.flowable.exposer.repository.SysExposeIndexJobRepository.class);
        vn.com.fecredit.flowable.exposer.service.MetadataResolver resolver = mock(vn.com.fecredit.flowable.exposer.service.MetadataResolver.class);
        IndexJobController controller = new IndexJobController(repo, resolver);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        String payload = "{ \"entityType\": \"order\", \"mappings\": [{ \"column\": \"total\", \"jsonPath\": \"$.total\", \"exportToPlain\": true, \"type\": \"decimal\" }] }";

        // Act & Assert
        mvc.perform(post("/api/index-job/preview").contentType("application/json").content(payload))
                .andExpect(status().isOk());
    }
}
