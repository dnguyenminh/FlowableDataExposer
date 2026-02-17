package vn.com.fecredit.simplesample.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import vn.com.fecredit.complexsample.repository.SysExposeClassDefRepository;
import vn.com.fecredit.complexsample.service.MetadataResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class MetadataControllerTest {

    MetadataController controller;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        controller = new MetadataController(mock(MetadataResolver.class), mock(SysExposeClassDefRepository.class), mock(org.springframework.context.ApplicationContext.class));
    }

    @Test
    void validate_rejects_missing_class_or_entityType() throws Exception {
        var node = mapper.readTree("{ } ");
        ResponseEntity<?> resp = controller.validate(node);
        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void validate_accepts_simple_valid_mapping() throws Exception {
        var node = mapper.readTree("{ \"_class\": \"Order\", \"entityType\": \"order\", \"mappings\": [{ \"column\": \"total\", \"jsonPath\": \"$.total\" }] }");
        ResponseEntity<?> resp = controller.validate(node);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
