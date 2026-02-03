package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class MetadataResolverTest {

    @Autowired
    MetadataResolver resolver;

    @Test
    void resolveAndExpandClassScopedMappings() {
        Map<String, vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition.FieldMapping> mappings = resolver.mappingsMetadataFor("Order");
        assertThat(mappings).isNotNull();
        assertThat(mappings).containsKey("customer_id");
        var fm = mappings.get("customer_id");
        assertThat(fm.jsonPath).isEqualTo("$.customer.id");

        // core must expose FlowableObject (audit parent) and core-level base classes
        var fo = resolver.resolveForClass("FlowableObject");
        assertThat(fo).isNotNull();
        assertThat(resolver.resolveForClass("WorkObject")).isNotNull();
        assertThat(resolver.resolveForClass("ProcessObject")).isNotNull();
        assertThat(resolver.resolveForClass("DataObject")).isNotNull();

        // FlowableObject must declare the audit/identity fields required by the design
        assertThat(fo.fields).extracting("name").contains("className", "createTime", "startUserId", "lastUpdated", "lastUpdateUserId", "updateTime", "creator", "updator", "tenantId");

        // domain-specific nested classes (Meta, ApprovalDecision) must live in web module and not be provided by core
        assertThat(resolver.resolveForClass("Meta")).isNull();
        assertThat(resolver.resolveForClass("ApprovalDecision")).isNull();
    }
}
