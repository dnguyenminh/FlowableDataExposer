package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import vn.com.fecredit.flowable.exposer.util.ModelDmnXmlExtractor;

import static org.assertj.core.api.Assertions.assertThat;

public class ModelDmnXmlExtractorTest {

    @Test
    void extract_returns_null_for_empty_or_invalid_xml() {
        byte[] xml = "<definitions></definitions>".getBytes();
        ModelDmnXmlExtractor.TableData td = ModelDmnXmlExtractor.extract(xml, 5, 5);
        assertThat(td).isNull();
    }

    @Test
    void extract_parses_simple_decision_table() {
        String xml = "<definitions xmlns=\"http://www.omg.org/spec/DMN/20151101/dmn.xsd\">" +
                "<decision id=\"d1\"><decisionTable>" +
                "<input><inputExpression><text>age</text></inputExpression></input>" +
                "<output name=\"result\"></output>" +
                "<rule><inputEntry><text> > 18 </text></inputEntry><outputEntry><text>adult</text></outputEntry></rule>" +
                "</decisionTable></decision></definitions>";
        ModelDmnXmlExtractor.TableData td = ModelDmnXmlExtractor.extract(xml.getBytes(), 10, 10);
        assertThat(td).isNotNull();
        assertThat(td.headers).contains("age", "result");
        assertThat(td.cells[0][0]).contains("> 18") ;
        assertThat(td.cells[0][1]).contains("adult");
    }
}
