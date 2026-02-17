package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class CaseDataWorkerHelpersTest {

    @Test
    void extractDirectFallbacks_reads_total_and_priority() throws Exception {
        String annotated = "{\"total\": 12.5, \"meta\": { \"priority\": \"HIGH\" } }";
        Map<String, Object> m = vn.com.fecredit.flowable.exposer.job.CaseDataWorkerHelpers.extractDirectFallbacks(annotated);
        assertThat(m).containsKey("total");
        assertThat(m.get("total")).isEqualTo(12.5d);
        assertThat(m).containsEntry("priority", "HIGH");
    }

    @Test
    void parsePayload_returns_empty_on_malformed() throws Exception {
        ObjectMapper om = new ObjectMapper();
        Map<String, Object> m = vn.com.fecredit.flowable.exposer.job.CaseDataWorkerHelpers.parsePayload(om, "not-a-json", "case-1");
        assertThat(m).isEmpty();
    }
}
