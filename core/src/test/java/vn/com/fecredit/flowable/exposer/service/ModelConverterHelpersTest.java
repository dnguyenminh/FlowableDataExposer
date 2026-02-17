package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import vn.com.fecredit.flowable.exposer.util.ModelConverterHelpers;
import vn.com.fecredit.flowable.exposer.util.ModelValidatorRenderer;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import static org.assertj.core.api.Assertions.assertThat;

public class ModelConverterHelpersTest {

    @Test
    void getSchemaForType_returns_null_when_no_schemas_present() {
        // project resources in test environment don't include Flowable XSDs; expect null
        assertThat(ModelConverterHelpers.getSchemaForType(ModelValidatorRenderer.ModelType.BPMN)).isNull();
    }

    @Test
    void tryConvertForType_does_not_throw_when_converters_maybe_present() throws Exception {
        String xml = "<definitions></definitions>";
        XMLStreamReader xtr = XMLInputFactory.newInstance().createXMLStreamReader(new java.io.StringReader(xml));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
            ModelConverterHelpers.tryConvertForType(ModelValidatorRenderer.ModelType.BPMN, xml.getBytes(), xtr);
        });
    }
}
