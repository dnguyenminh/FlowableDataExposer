package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class MetadataBaseClassesTest {

    @Autowired
    MetadataResolver resolver;

    @Test
    void coreBaseClasses_declare_framework_fields() {
        MetadataDefinition fo = resolver.resolveForClass("FlowableObject");
        assertThat(fo).isNotNull();
        List<String> foNames = fo.fields.stream().map(f -> f.name).collect(Collectors.toList());
        assertThat(foNames).containsExactlyInAnyOrder(
                "className", "createTime", "startUserId",
                "lastUpdated", "lastUpdateUserId",
                "updateTime", "creator", "updator", "tenantId"
        );

        MetadataDefinition work = resolver.resolveForClass("WorkObject");
        assertThat(work).isNotNull();
        assertThat(work.parent).isEqualTo("FlowableObject");
        List<String> workNames = work.fields.stream().map(f -> f.name).collect(Collectors.toList());
        assertThat(workNames).containsExactlyInAnyOrder("caseInstanceId", "businessKey", "state");

        MetadataDefinition proc = resolver.resolveForClass("ProcessObject");
        assertThat(proc).isNotNull();
        assertThat(proc.parent).isEqualTo("FlowableObject");
        List<String> procNames = proc.fields.stream().map(f -> f.name).collect(Collectors.toList());
        assertThat(procNames).containsExactlyInAnyOrder("processInstanceId", "processDefinitionId", "parentInstanceId");

        MetadataDefinition data = resolver.resolveForClass("DataObject");
        assertThat(data).isNotNull();
        assertThat(data.parent).isEqualTo("FlowableObject");
        List<String> dataNames = data.fields.stream().map(f -> f.name).collect(Collectors.toList());
        assertThat(dataNames).containsExactlyInAnyOrder("id", "type");
    }
}
