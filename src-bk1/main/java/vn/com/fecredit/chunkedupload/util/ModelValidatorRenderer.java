package vn.com.fecredit.chunkedupload.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.flowable.bpmn.model.BpmnModel;
import org.w3c.dom.Document;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

/**
 * Utility to validate (XSD + engine converter) and render (image) BPMN/CMMN/DMN models.
 *
 * - Validation: uses local XSDs when available and also attempts conversion with
 *   Flowable XML converters to catch engine-level parsing errors.
 * - Rendering: uses Flowable image generators to produce PNG files.
 *
 * Designed for use in dev/CI to validate model artifacts and produce human-friendly
 * thumbnails for review.
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
        try (InputStream is = Files.newInputStream(xml)) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(is);
            String ns = doc.getDocumentElement().getNamespaceURI();
            if ("http://www.omg.org/spec/CMMN/20151109/MODEL".equals(ns)) {
                return ModelType.CMMN;
            } else if ("http://www.omg.org/spec/BPMN/20100524/MODEL".equals(ns) || "http://www.omg.org/spec/BPMN/20100524/MODEL".equals(ns)) {
                return ModelType.BPMN;
            } else if (ns != null && ns.contains("dmn")) {
                return ModelType.DMN;
            }
            return ModelType.UNKNOWN;
        }
    }

    /**
     * Validate XML against provided XSDs (if available in classpath/resources) and
     * additionally by attempting to convert with the Flowable XML converters.
     */
    public static ValidationResult validate(Path xml) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            ModelType type = detectType(xml);
            // 1) XSD validation (fatal)
            Schema schema = getSchemaForType(type);
            if (schema != null) {
                try {
                    Validator v = schema.newValidator();
                    v.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                        @Override
                        public void error(org.xml.sax.SAXParseException e) {
                            errors.add(e.getMessage());
                        }

                        @Override
                        public void fatalError(org.xml.sax.SAXParseException e) {
                            errors.add(e.getMessage());
                        }

                        @Override
                        public void warning(org.xml.sax.SAXParseException e) {
                            // treat XSD warnings as non-fatal but record them
                            warnings.add(e.getMessage());
                        }
                    });
                    try (InputStream xmlIs = Files.newInputStream(xml)) {
                        v.validate(new StreamSource(xmlIs));
                    }
                } catch (Exception ex) {
                    errors.add(ex.getMessage());
                }
            }

            // 2) engine-level parsing (non-fatal; keep as warning)
            try (InputStream xmlIs = Files.newInputStream(xml)) {
                byte[] bytes = xmlIs.readAllBytes();
                XMLInputFactory xif = XMLInputFactory.newInstance();
                XMLStreamReader xtr = xif.createXMLStreamReader(new ByteArrayInputStream(bytes));
                try {
                    switch (type) {
                        case CMMN: {
                            Object model = tryConvertForType(ModelType.CMMN, bytes, xtr);
                            if (model == null) {
                                warnings.add("CMMN converter unavailable or returned null model");
                            }
                            break;
                        }
                        case BPMN: {
                            Object model = tryConvertForType(ModelType.BPMN, bytes, xtr);
                            if (model == null) {
                                warnings.add("BPMN converter unavailable or returned null model");
                            }
                            break;
                        }
                        case DMN: {
                            Object model = tryConvertForType(ModelType.DMN, bytes, xtr);
                            if (model == null) {
                                warnings.add("DMN converter unavailable or returned null model");
                            }
                            break;
                        }
                        default:
                            warnings.add("Unknown model type — skipped engine-level conversion");
                    }
                } catch (Exception e) {
                    warnings.add("Engine-level parsing raised exception: " + e.getMessage());
                }
            }

            // 1.b) For CMMN, run a lightweight DI-consistency check (non-fatal)
            if (type == ModelType.CMMN) {
                try (InputStream xmlIs = Files.newInputStream(xml)) {
                    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                    dbf.setNamespaceAware(true);
                    DocumentBuilder db = dbf.newDocumentBuilder();
                    Document doc = db.parse(xmlIs);
                    // collect all ids defined in the CMMN namespace
                    java.util.Set<String> ids = new java.util.HashSet<>();
                    org.w3c.dom.NodeList all = doc.getElementsByTagNameNS("http://www.omg.org/spec/CMMN/20151109/MODEL", "*");
                    for (int i = 0; i < all.getLength(); i++) {
                        org.w3c.dom.Element el = (org.w3c.dom.Element) all.item(i);
                        if (el.hasAttribute("id")) {
                            ids.add(el.getAttribute("id"));
                        }
                    }
                    // find cmmndi shapes and verify their cmmnElementRef exists
                    org.w3c.dom.NodeList shapes = doc.getElementsByTagNameNS("http://www.omg.org/spec/CMMN/20151109/CMMNDI", "CMMNShape");
                    for (int i = 0; i < shapes.getLength(); i++) {
                        org.w3c.dom.Element s = (org.w3c.dom.Element) shapes.item(i);
                        String ref = s.getAttribute("cmmnElementRef");
                        if (ref != null && !ref.isEmpty() && !ids.contains(ref)) {
                            warnings.add("CMMN DI shape references unknown element '" + ref + "' — diagram may not render correctly");
                        }
                    }
                } catch (Exception ex) {
                    // non-fatal; add as warning
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
        return new ValidationResult(ok, ok && messages.isEmpty() ? List.of("OK") : messages);
    }

    /**
     * Render the model to a PNG file. Returns the output path.
     * Throws IOException on I/O errors or IllegalStateException when rendering is not possible.
     */
    public static Path renderToPng(Path xml, Path outputFile) throws IOException {
        Objects.requireNonNull(xml);
        Objects.requireNonNull(outputFile);
        try {
            ModelType type = detectType(xml);
            byte[] imgBytes = null;
            switch (type) {
                case BPMN: {
                    byte[] xmlBytes = Files.readAllBytes(xml);
                    XMLInputFactory xif = XMLInputFactory.newInstance();
                    XMLStreamReader xtr = xif.createXMLStreamReader(new ByteArrayInputStream(xmlBytes));
                    Object bpmnModel = tryConvertForType(ModelType.BPMN, xmlBytes, xtr);
                    if (bpmnModel == null) {
                        throw new IllegalStateException("BPMN converter not available on classpath");
                    }
                    // Use the ProcessDiagramGenerator (present in flowable-image-generator)
                    Object gen = Class.forName("org.flowable.image.impl.DefaultProcessDiagramGenerator").getDeclaredConstructor().newInstance();
                    java.lang.reflect.Method m = gen.getClass().getMethod("generateDiagram", org.flowable.bpmn.model.BpmnModel.class, String.class, java.util.List.class, java.util.List.class, String.class, String.class, String.class, ClassLoader.class, double.class, boolean.class);
                    try (InputStream is = (InputStream) m.invoke(gen, bpmnModel, "png", Collections.emptyList(), Collections.emptyList(), "Arial", "Arial", "Arial", ModelValidatorRenderer.class.getClassLoader(), 1.0d, Boolean.FALSE)) {
                        imgBytes = is.readAllBytes();
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        Throwable cause = ite.getCause() == null ? ite : ite.getCause();
                        throw new IOException("Diagram rendering failed: " + cause.getMessage(), cause);
                    }
                    break;
                }
                case CMMN: {
                    byte[] xmlBytes = Files.readAllBytes(xml);
                    XMLInputFactory xif = XMLInputFactory.newInstance();
                    XMLStreamReader xtr = xif.createXMLStreamReader(new ByteArrayInputStream(xmlBytes));
                    Object cmmnModel = tryConvertForType(ModelType.CMMN, xmlBytes, xtr);
                    if (cmmnModel == null) {
                        throw new IllegalStateException("CMMN converter not available on classpath");
                    }
                    Object gen = Class.forName("org.flowable.cmmn.image.impl.DefaultCaseDiagramGenerator").getDeclaredConstructor().newInstance();
                    java.lang.reflect.Method mm = gen.getClass().getMethod("generateDiagram", Class.forName("org.flowable.cmmn.model.CmmnModel"), String.class);
                    try (InputStream is = (InputStream) mm.invoke(gen, cmmnModel, "png")) {
                        imgBytes = is.readAllBytes();
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        Throwable cause = ite.getCause() == null ? ite : ite.getCause();
                        throw new IOException("Diagram rendering failed (CMMN DI may be inconsistent): " + cause.getMessage(), cause);
                    }
                    break;
                }
                case DMN: {
                    byte[] xmlBytes = Files.readAllBytes(xml);
                    XMLInputFactory xif = XMLInputFactory.newInstance();
                    XMLStreamReader xtr = xif.createXMLStreamReader(new ByteArrayInputStream(xmlBytes));
                    Object dmnModel = tryConvertForType(ModelType.DMN, xmlBytes, xtr);
                    if (dmnModel == null) {
                        throw new IllegalStateException("DMN converter not available on classpath");
                    }
                    Class<?> drdGenClass = Class.forName("org.flowable.dmn.image.impl.DefaultDecisionRequirementsDiagramGenerator");
                    Object drdGen = drdGenClass.getDeclaredConstructor().newInstance();
                    java.lang.reflect.Method m = drdGenClass.getMethod("generateDiagram", Class.forName("org.flowable.dmn.model.DmnDefinition"), String.class);
                    try (InputStream is = (InputStream) m.invoke(drdGen, dmnModel, "png")) {
                        imgBytes = is.readAllBytes();
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        Throwable cause = ite.getCause() == null ? ite : ite.getCause();
                        throw new IOException("DMN diagram rendering failed: " + cause.getMessage(), cause);
                    }
                    break;
                }
                default:
                    throw new IllegalStateException("Unsupported model type for rendering: " + detectType(xml));
            }

            if (imgBytes == null || imgBytes.length == 0) {
                throw new IOException("Renderer returned no image bytes");
            }

            // Improve visibility for thumbnails produced by Flowable renderers (transparent bg)
            imgBytes = enhanceThumbnail(imgBytes);

             Files.createDirectories(outputFile.getParent());
             try (FileOutputStream fos = new FileOutputStream(outputFile.toFile())) {
                 fos.write(imgBytes);
             }
             return outputFile;
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
+
+    // Same enhancement as main implementation: composite transparent PNG onto white and draw border
+    private static byte[] enhanceThumbnail(byte[] pngBytes) throws IOException {
+        BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngBytes));
+        if (src == null) return pngBytes;
+        final int w = src.getWidth();
+        final int h = src.getHeight();
+        if (!src.getColorModel().hasAlpha()) return pngBytes;
+        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
+        java.awt.Graphics2D g = out.createGraphics();
+        try {
+            g.setColor(java.awt.Color.WHITE);
+            g.fillRect(0, 0, w, h);
+            g.drawImage(src, 0, 0, null);
+            int minX = w, minY = h, maxX = -1, maxY = -1;
+            for (int y = 0; y < h; y++) {
+                for (int x = 0; x < w; x++) {
+                    int argb = src.getRGB(x, y);
+                    int a = (argb >>> 24) & 0xff;
+                    if (a > 16) {
+                        if (x < minX) minX = x;
+                        if (y < minY) minY = y;
+                        if (x > maxX) maxX = x;
+                        if (y > maxY) maxY = y;
+                    }
+                }
+            }
+            if (maxX >= 0) {
+                int pad = Math.max(2, Math.min(12, Math.max(w, h) / 200));
+                int bx = Math.max(0, minX - pad);
+                int by = Math.max(0, minY - pad);
+                int bw = Math.min(w - 1, maxX + pad) - bx + 1;
+                int bh = Math.min(h - 1, maxY + pad) - by + 1;
+                java.awt.BasicStroke stroke = new java.awt.BasicStroke(Math.max(1f, Math.min(3f, Math.max(w, h) / 500f)));
+                g.setStroke(stroke);
+                g.setColor(new java.awt.Color(0, 0, 0, 48));
+                g.drawRect(bx + 1, by + 1, bw, bh);
+                g.setColor(new java.awt.Color(0, 0, 0, 96));
+                g.drawRect(bx, by, bw, bh);
+            }
+        } finally {
+            g.dispose();
+        }
+        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
+        ImageIO.write(out, "png", baos);
+        return baos.toByteArray();
+    }
    // Simple CLI for quick usage from IDE/terminal
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: ModelValidatorRenderer <path-to-model> [--out out.png]");
            return;
        }
        Path in = Path.of(args[0]);
        Path out = null;
        for (int i = 1; i < args.length; i++) {
            if ("--out".equals(args[i]) && i + 1 < args.length) {
                out = Path.of(args[++i]);
            }
        }
        ValidationResult vr = validate(in);
        System.out.println("Validation: " + (vr.valid ? "OK" : "FAILED"));
        vr.messages.forEach(m -> System.out.println(" - " + m));
        if (!vr.valid) {
            System.exit(2);
        }
        if (out == null) {
            out = in.getParent().resolve(in.getFileName().toString() + ".png");
        }
        Path written = renderToPng(in, out);
        System.out.println("Wrote image: " + written.toAbsolutePath());
    }

    // Attempt to load a Flowable converter reflectively and invoke a convertTo* method that accepts
    // an XMLStreamReader. Returns the model object or null when the converter class is not present.
    private static Object tryConvertReflectively(String converterClassName, XMLStreamReader xtr) throws Exception {
        return tryConvertReflectively(converterClassName, null, xtr);
    }

    private static Object tryConvertReflectively(String converterClassName, byte[] xmlBytes, XMLStreamReader xtr) throws Exception {
        try {
            Class<?> convClass = Class.forName(converterClassName);
            Object conv = convClass.getDeclaredConstructor().newInstance();

            // 1) prefer XMLStreamReader-based conversion
            for (java.lang.reflect.Method method : convClass.getMethods()) {
                if (method.getName().startsWith("convertTo") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isAssignableFrom(XMLStreamReader.class)) {
                    try {
                        return method.invoke(conv, xtr);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        throw ite.getCause() != null ? new Exception(ite.getCause()) : ite;
                    }
                }
            }

            // 2) try InputStream-based conversion
            if (xmlBytes != null) {
                for (java.lang.reflect.Method method : convClass.getMethods()) {
                    if (method.getName().startsWith("convertTo") && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].isAssignableFrom(java.io.InputStream.class)) {
                        try {
                            return method.invoke(conv, new ByteArrayInputStream(xmlBytes));
                        } catch (java.lang.reflect.InvocationTargetException ite) {
                            throw ite.getCause() != null ? new Exception(ite.getCause()) : ite;
                        }
                    }
                }
            }

            // 3) try to detect an InputStreamProvider parameter (common in Flowable converters)
            for (java.lang.reflect.Method method : convClass.getMethods()) {
                if (method.getName().startsWith("convertTo") && method.getParameterCount() == 1) {
                    Class<?> ptype = method.getParameterTypes()[0];
                    if (ptype.getSimpleName().toLowerCase().contains("inputstreamprovider") && xmlBytes != null) {
                        // build a tiny anonymous implementation if the interface is visible
                        Object provider = java.lang.reflect.Proxy.newProxyInstance(ptype.getClassLoader(), new Class<?>[] { ptype }, (proxy, m, args) -> {
                            if ("getInputStream".equals(m.getName()) || "getInputStreamProvider".equals(m.getName())) {
                                return new ByteArrayInputStream(xmlBytes);
                            }
                            throw new UnsupportedOperationException(m.getName());
                        });
                        try {
                            return method.invoke(conv, provider);
                        } catch (java.lang.reflect.InvocationTargetException ite) {
                            throw ite.getCause() != null ? new Exception(ite.getCause()) : ite;
                        }
                    }
                }
            }

            // No suitable method found — surface available signatures for debugging
            StringBuilder sb = new StringBuilder();
            sb.append("Converter ").append(converterClassName).append(" present but no compatible convertTo* method found. Available methods:\n");
            for (java.lang.reflect.Method method : convClass.getMethods()) {
                if (method.getName().startsWith("convertTo")) {
                    sb.append("  ").append(method.toString()).append('\n');
                }
            }
            throw new IllegalStateException(sb.toString());
        } catch (ClassNotFoundException cnf) {
            return null;
        }
    }

    // Helper that tries multiple well-known converter class names for a model type.
    private static Object tryConvertForType(ModelType type, byte[] xmlBytes, XMLStreamReader xtr) throws Exception {
        String[] candidates;
        switch (type) {
            case CMMN:
                candidates = new String[] { "org.flowable.cmmn.converter.CmmnXMLConverter", "org.flowable.cmmn.converter.CmmnXmlConverter" };
                break;
            case BPMN:
                candidates = new String[] { "org.flowable.bpmn.converter.BpmnXMLConverter", "org.flowable.bpmn.converter.BpmnXmlConverter" };
                break;
            case DMN:
                candidates = new String[] { "org.flowable.dmn.xml.converter.DmnXMLConverter", "org.flowable.dmn.xml.converter.DmnXmlConverter", "org.flowable.dmn.converter.DmnXMLConverter" };
                break;
            default:
                return null;
        }
        for (String c : candidates) {
            try {
                Object m = tryConvertReflectively(c, xmlBytes, xtr);
                if (m != null) {
                    return m;
                }
            } catch (ClassNotFoundException cnf) {
                // try next
            } catch (IllegalStateException ise) {
                // converter present but incompatible — surface upwards
                throw ise;
            }
        }
        return null;
    }

    private static Schema getSchemaForType(ModelType type) {
        try {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            List<StreamSource> sources = new ArrayList<>();
            String base = "/xsd/";
            switch (type) {
                case CMMN:
                    addIfFound(sources, base + "CMMN11.xsd");
                    addIfFound(sources, base + "CMMN11CaseModel.xsd");
                    addIfFound(sources, base + "CMMNDI11.xsd");
                    addIfFound(sources, base + "DC.xsd");
                    addIfFound(sources, base + "DI.xsd");
                    addIfFound(sources, base + "flowable-cmmn.xsd");
                    addIfFound(sources, base + "flowable-design.xsd");
                    break;
                case BPMN:
                    addIfFound(sources, base + "BPMN20.xsd");
                    addIfFound(sources, base + "BPMNDI.xsd");
                    addIfFound(sources, base + "DC.xsd");
                    addIfFound(sources, base + "DI.xsd");
                    addIfFound(sources, base + "flowable-bpmn-extensions.xsd");
                    break;
                case DMN:
                    addIfFound(sources, base + "dmn.xsd");
                    addIfFound(sources, base + "DMN13.xsd");
                    addIfFound(sources, base + "DC.xsd");
                    addIfFound(sources, base + "DI.xsd");
                    break;
                default:
                    return null;
            }
            if (sources.isEmpty()) {
                return null;
            }
            return sf.newSchema(sources.toArray(new StreamSource[0]));
        } catch (Exception e) {
            return null;
        }
    }

    private static void addIfFound(List<StreamSource> out, String resourcePath) {
        InputStream is = ModelValidatorRenderer.class.getResourceAsStream(resourcePath);
        if (is != null) {
            out.add(new StreamSource(is));
        }
    }
}
