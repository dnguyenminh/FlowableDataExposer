package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

public class MetadataBaseClassesTest {

    private MetadataResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = MetadataResolverTestHelper.createMetadataResolver();
    }

    //@Test
    void coreBaseClasses_declare_framework_fields() {
        // Test that framework base classes exist and have correct hierarchy
        var fo = resolver.resolveForClass("FlowableObject");
        System.out.println("FlowableObject _class: " + fo._class + ", parent: " + fo.parent);
        assertThat(fo).isNotNull();
        assertThat(fo._class).isEqualTo("FlowableObject");

        var work = resolver.resolveForClass("WorkObject");
        System.out.println("WorkObject _class: " + work._class + ", parent: " + work.parent);
        assertThat(work).isNotNull();
        assertThat(work.parent != null ? work.parent.trim() : null).isEqualTo("FlowableObject");

        var proc = resolver.resolveForClass("ProcessObject");
        System.out.println("ProcessObject _class: " + proc._class + ", parent: " + proc.parent);
        assertThat(proc).isNotNull();
        assertThat(proc.parent != null ? proc.parent.trim() : null).isEqualTo("FlowableObject");

        var data = resolver.resolveForClass("DataObject");
        System.out.println("DataObject _class: " + data._class + ", parent: " + data.parent);
        assertThat(data).isNotNull();
        assertThat(data.parent != null ? data.parent.trim() : null).isEqualTo("FlowableObject");
    }
}
