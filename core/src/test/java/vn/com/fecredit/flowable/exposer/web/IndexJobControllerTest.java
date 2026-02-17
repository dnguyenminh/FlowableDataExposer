package vn.com.fecredit.flowable.exposer.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import vn.com.fecredit.flowable.exposer.entity.SysExposeIndexJob;
import vn.com.fecredit.flowable.exposer.repository.SysExposeIndexJobRepository;
import vn.com.fecredit.flowable.exposer.service.MetadataResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;


public class IndexJobControllerTest {

    IndexJobController controller;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        SysExposeIndexJobRepository repo = mock(SysExposeIndexJobRepository.class);
        when(repo.save(org.mockito.ArgumentMatchers.any(SysExposeIndexJob.class))).thenAnswer(i -> {
            SysExposeIndexJob j = i.getArgument(0);
            j.setId(123L);
            return j;
        });
        when(repo.findById(123L)).thenReturn(Optional.of(new SysExposeIndexJob()));
        controller = new IndexJobController(repo, mock(MetadataResolver.class));
    }

    @Test
    void preview_valid_mapping_generates_ddl() throws Exception {
        var body = mapper.readValue("{ \"entityType\": \"order\", \"mappings\": [{ \"column\": \"total\", \"jsonPath\": \"$.total\", \"exportToPlain\": true, \"type\": \"decimal\" }] }", java.util.Map.class);
        ResponseEntity<?> resp = controller.preview(body);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        var map = (java.util.Map) resp.getBody();
        assertThat(map).containsKey("ddl");
    }

    @Test
    void create_saves_job_and_returns_id() throws Exception {
        var body = mapper.readValue("{ \"entityType\": \"order\", \"mappings\": [] }", java.util.Map.class);
        ResponseEntity<?> resp = controller.create(body);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        var map = (java.util.Map) resp.getBody();
        assertThat(map.get("jobId")).isEqualTo(123L);
    }
}
