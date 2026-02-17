package vn.com.fecredit.flowable.exposer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef;
import vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository;
import vn.com.fecredit.flowable.exposer.service.MetadataResolver;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class MetadataControllerTest {

    private MetadataController controller;
    ObjectMapper mapper = new ObjectMapper();
    private MetadataResolver metadataResolver = mock(MetadataResolver.class);
    private SysExposeClassDefRepository sysExposeClassDefRepository = mock(SysExposeClassDefRepository.class);
    private ApplicationContext applicationContext = mock(ApplicationContext.class);


    @BeforeEach
    void setUp() {
        controller = new MetadataController(metadataResolver, sysExposeClassDefRepository, applicationContext);
    }

    @Test
    void validate_rejects_missing_class_or_entityType() throws Exception {
        var node = mapper.readTree("{ } ");
        ResponseEntity<?> resp = controller.validate(node);
        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void validate_accepts_simple_valid_mapping() throws Exception {
        var node = mapper.readTree("{ \"class\": \"Order\", \"entityType\": \"order\", \"mappings\": [{ \"column\": \"total\", \"jsonPath\": \"$.total\" }] }");
        ResponseEntity<?> resp = controller.validate(node);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void validate_rejects_invalid_jsonPath() throws Exception {
        var node = mapper.readTree("{ \"class\": \"Order\", \"entityType\": \"order\", \"mappings\": [{ \"column\": \"total\", \"jsonPath\": \"$.total..\" }] }");
        ResponseEntity<?> resp = controller.validate(node);
        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void validate_rejects_missing_column_in_mapping() throws Exception {
        var node = mapper.readTree("{ \"class\": \"Order\", \"entityType\": \"order\", \"mappings\": [{ \"jsonPath\": \"$.total\" }] }");
        ResponseEntity<?> resp = controller.validate(node);
        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void validate_rejects_missing_jsonPath_in_mapping() throws Exception {
        var node = mapper.readTree("{ \"class\": \"Order\", \"entityType\": \"order\", \"mappings\": [{ \"column\": \"total\" }] }");
        ResponseEntity<?> resp = controller.validate(node);
        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void fieldCheck_returns_extracted_data_for_valid_payload() throws Exception {
        when(metadataResolver.mappingsFor("Order")).thenReturn(Map.of("total_amount", "$.total"));
        JsonNode sampleBlob = mapper.readTree("{ \"total\": 12.5 }");
        var node = mapper.createObjectNode()
                .put("class", "Order")
                .set("sampleBlob", sampleBlob);

        ResponseEntity<?> resp = controller.fieldCheck(node);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).isInstanceOf(Map.class);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsKey("extracted");
        Map<String, Object> extracted = (Map<String, Object>) body.get("extracted");
        assertThat(extracted).containsKey("total_amount");
        assertThat(extracted.get("total_amount")).isEqualTo(12.5);
    }

    @Test
    void apply_endpoint_persists_metadata_to_db() throws Exception {
        when(sysExposeClassDefRepository.save(any(SysExposeClassDef.class))).thenAnswer(i -> i.getArgument(0));

        var node = mapper.readTree("{\n  \"class\": \"OrderX\",\n  \"entityType\": \"OrderX\",\n  \"mappings\": [{ \"column\": \"total_amount\", \"jsonPath\": \"$.total\", \"exportToPlain\": true, \"plainColumn\": \"order_total\" }]\n}");

        ResponseEntity<?> resp = controller.apply(node);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).isInstanceOf(Map.class);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsKey("imported");
        assertThat(body.get("imported")).isEqualTo("OrderX");
    }

    @Test
    void reindex_throws_illegal_state_exception_when_caseDataWorker_is_not_available() {
        when(applicationContext.containsBean("caseDataWorker")).thenReturn(false);
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{});

        ResponseEntity<?> resp = controller.reindex("someEntityType");

        assertThat(resp.getStatusCode().is5xxServerError()).isTrue();
        assertThat(resp.getBody()).isInstanceOf(Map.class);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsKey("error");
        assertThat(body.get("error")).isEqualTo("CaseDataWorker bean not available");
    }
}
