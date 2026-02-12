package vn.com.fecredit.flowable.exposer.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;

/**
 * Extracted validation logic from ModelValidatorRenderer to reduce file size.
 */
public final class ModelValidationHelpers {
    private ModelValidationHelpers() {}

    public static ModelValidatorRenderer.ValidationResult validate(Path xml) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            ModelValidatorRenderer.ModelType type = ModelValidatorRenderer.detectType(xml);

            // XSD validation (fatal)
            Schema schema = ModelConverterHelpers.getSchemaForType(type);
            if (schema != null) {
                try {
                    javax.xml.validation.Validator v = schema.newValidator();
                    v.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                        @Override public void error(org.xml.sax.SAXParseException e) { errors.add(e.getMessage()); }
                        @Override public void fatalError(org.xml.sax.SAXParseException e) { errors.add(e.getMessage()); }
                        @Override public void warning(org.xml.sax.SAXParseException e) { warnings.add(e.getMessage()); }
                    });
                    try (InputStream xmlIs = Files.newInputStream(xml)) {
                        v.validate(new StreamSource(xmlIs));
                    }
                } catch (Exception ex) {
                    errors.add(ex.getMessage());
                }
            }

            // Engine-level parsing (non-fatal)
            try (InputStream xmlIs = Files.newInputStream(xml)) {
                byte[] bytes = xmlIs.readAllBytes();
                XMLInputFactory xif = XMLInputFactory.newInstance();
                XMLStreamReader xtr = xif.createXMLStreamReader(new ByteArrayInputStream(bytes));
                try {
                    switch (type) {
                        case CMMN: {
                            Object model = ModelConverterHelpers.tryConvertForType(ModelValidatorRenderer.ModelType.CMMN, bytes, xtr);
                            if (model == null) warnings.add("CMMN converter unavailable or returned null model");
                            break;
                        }
                        case BPMN: {
                            Object model = ModelConverterHelpers.tryConvertForType(ModelValidatorRenderer.ModelType.BPMN, bytes, xtr);
                            if (model == null) warnings.add("BPMN converter unavailable or returned null model");
                            break;
                        }
                        case DMN: {
                            Object model = ModelConverterHelpers.tryConvertForType(ModelValidatorRenderer.ModelType.DMN, bytes, xtr);
                            if (model == null) warnings.add("DMN converter unavailable or returned null model");
                            break;
                        }
                        default:
                            warnings.add("Unknown model type — skipped engine-level conversion");
                    }
                } catch (Exception e) {
                    warnings.add("Engine-level parsing raised exception: " + e.getMessage());
                }
            }

            // CMMN DI sanity check
            if (ModelValidatorRenderer.detectType(xml) == ModelValidatorRenderer.ModelType.CMMN) {
                try (InputStream xmlIs = Files.newInputStream(xml)) {
                    javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                    dbf.setNamespaceAware(true);
                    javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
                    org.w3c.dom.Document doc = db.parse(xmlIs);
                    java.util.Set<String> ids = new java.util.HashSet<>();
                    org.w3c.dom.NodeList all = doc.getElementsByTagNameNS("http://www.omg.org/spec/CMMN/20151109/MODEL", "*");
                    for (int i = 0; i < all.getLength(); i++) {
                        org.w3c.dom.Element el = (org.w3c.dom.Element) all.item(i);
                        if (el.hasAttribute("id")) ids.add(el.getAttribute("id"));
                    }
                    org.w3c.dom.NodeList shapes = doc.getElementsByTagNameNS("http://www.omg.org/spec/CMMN/20151109/CMMNDI", "CMMNShape");
                    for (int i = 0; i < shapes.getLength(); i++) {
                        org.w3c.dom.Element s = (org.w3c.dom.Element) shapes.item(i);
                        String ref = s.getAttribute("cmmnElementRef");
                        if (ref != null && !ref.isEmpty() && !ids.contains(ref)) {
                            warnings.add("CMMN DI shape references unknown element '" + ref + "' — diagram may not render correctly");
                        }
                    }
                } catch (Exception ex) {
                    warnings.add("CMMN DI consistency check failed: " + ex.getMessage());
                }
            }

        } catch (Exception e) {
            errors.add("Exception during validation: " + e.getMessage());
        }

        List<String> messages = new ArrayList<>();
        if (!errors.isEmpty()) {
            messages.add("XSD errors:");
            messages.addAll(errors);
        }
        if (!warnings.isEmpty()) {
            messages.add("(warning) engine-level parse issues:");
            messages.addAll(warnings);
        }

        boolean ok = errors.isEmpty();
        return new ModelValidatorRenderer.ValidationResult(ok, ok && messages.isEmpty() ? List.of("OK") : messages);
    }
}
