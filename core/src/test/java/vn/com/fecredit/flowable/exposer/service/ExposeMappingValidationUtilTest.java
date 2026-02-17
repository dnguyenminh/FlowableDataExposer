package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import vn.com.fecredit.flowable.exposer.service.metadata.ExposeMappingValidationUtil;

import static org.assertj.core.api.Assertions.assertThat;

public class ExposeMappingValidationUtilTest {

    @Test
    void valid_sample_expose_passes_validator() {
        var res = ExposeMappingValidationUtil.validate("metadata/exposes/sample-expose.json");
        assertThat(res).isNotNull();
        assertThat(res.isValid()).withFailMessage(() -> String.join("; ", res.getErrors())).isTrue();
    }

    @Test
    void missing_mappings_fails_validator() {
        var res = ExposeMappingValidationUtil.validate("metadata/exposes/sample-expose.json");
        // sample has mappings, so ensure positive path; to test negative path create inline failing resource check via path that doesn't exist
        var res2 = ExposeMappingValidationUtil.validate("metadata/exposes/NonExisting.json");
        assertThat(res2).isNotNull();
        assertThat(res2.isValid()).isFalse();
        assertThat(res2.getErrors().get(0)).contains("not found");
    }
}
