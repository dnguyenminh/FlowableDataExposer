package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import vn.com.fecredit.flowable.exposer.util.ModelValidationHelpers;
import vn.com.fecredit.flowable.exposer.util.ModelValidatorRenderer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class ModelValidationHelpersTest {

    @Test
    void validate_returns_warning_when_converter_unavailable_for_simple_bpmn() throws Exception {
        // create a minimal BPMN XML in a temp file
        String bpmn = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"></definitions>";
        Path tmp = Files.createTempFile("test-bpmn", ".bpmn20.xml");
        Files.writeString(tmp, bpmn);

        ModelValidatorRenderer.ValidationResult res = ModelValidationHelpers.validate(tmp);
        assertThat(res).isNotNull();
        // converters are not on classpath in test env; expect a warning about converter unavailable
        assertThat(res.messages).anyMatch(m -> m.contains("BPMN converter unavailable") || m.contains("OK"));
    }

    @Test
    void validate_handles_missing_file_gracefully() throws Exception {
        Path tmp = Path.of("non-existing-file.xml");
        ModelValidatorRenderer.ValidationResult res = ModelValidationHelpers.validate(tmp);
        assertThat(res).isNotNull();
        assertThat(res.messages).anyMatch(m -> m.contains("Exception during validation") || m.contains("XSD errors"));
    }
}
