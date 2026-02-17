package vn.com.fecredit.complexsample.util;

import java.nio.file.Path;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Top-level validator/renderer facade. Heavy lifting delegated to helpers to keep this file small.
 */
public final class ModelValidatorRenderer {

    private ModelValidatorRenderer() {}

    public enum ModelType { CMMN, BPMN, DMN, UNKNOWN }

    public static class ValidationResult {
        public final boolean valid;
        public final List<String> messages;

        public ValidationResult(boolean valid, List<String> messages) {
            this.valid = valid;
            this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        }
    }

    public static ModelType detectType(Path xml) throws Exception {
        Objects.requireNonNull(xml, "xml");
        try (InputStream is = java.nio.file.Files.newInputStream(xml)) {
            javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
            org.w3c.dom.Document doc = db.parse(is);
            String ns = doc.getDocumentElement().getNamespaceURI();
            if ("http://www.omg.org/spec/CMMN/20151109/MODEL".equals(ns)) return ModelType.CMMN;
            if ("http://www.omg.org/spec/BPMN/20100524/MODEL".equals(ns)) return ModelType.BPMN;
            if (ns != null && ns.contains("dmn")) return ModelType.DMN;
            return ModelType.UNKNOWN;
        }
    }

    public static ValidationResult validate(Path xml) {
        return ModelValidationHelpers.validate(xml);
    }

    public static Path renderToPng(Path xml, Path outputFile) throws java.io.IOException {
        return ModelRenderHelpers.renderToPng(xml, outputFile);
    }
}
