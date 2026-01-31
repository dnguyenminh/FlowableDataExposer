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

                    // If the Flowable DMN image generator returned a visually-empty/blank image
                    // (common when the DMN XML contains no DI/DRD shapes), create a compact
                    // informative placeholder that shows decision name and counts (inputs/outputs/rules).
                    try {
                        BufferedImage produced = null;
                        if (imgBytes != null && imgBytes.length > 0) {
                            produced = ImageIO.read(new ByteArrayInputStream(imgBytes));
                        }
                        if (produced == null || isMostlyBlank(produced)) {
                            // create a readable placeholder from the parsed DMN model
                            int w = (produced != null) ? produced.getWidth() : 1000;
                            int h = (produced != null) ? produced.getHeight() : 600;
                            imgBytes = createDmnPlaceholder(dmnModel, w, h);
                        }
                        // safety net: if bytes are unexpectedly tiny, replace with a deterministic placeholder
                        if (imgBytes == null || imgBytes.length < 1024) {
                            imgBytes = createSimplePlaceholderPng("DMN summary", 1000, 600);
                        }
                    } catch (Throwable _t) {
                        // non-fatal: fall back to whatever the generator returned (or let downstream handle empty)
                    }
                    break;
                }
                default:
                    throw new IllegalStateException("Unsupported model type for rendering: " + detectType(xml));
            }

            if (imgBytes == null || imgBytes.length == 0) {
                throw new IOException("Renderer returned no image bytes");
            }

            // Post-process to improve visibility in model thumbnails:
            // - Composite any transparent PNG onto a white background so diagram borders are visible
            // - Draw a faint outline around the non-transparent content to emphasise stages/boxes
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

    // Improve visibility for thumbnails produced by Flowable renderers which often
    // emit fully-transparent backgrounds and thin DI strokes. Returns new PNG bytes.
    private static byte[] enhanceThumbnail(byte[] pngBytes) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (src == null) return pngBytes;

        final int w = src.getWidth();
        final int h = src.getHeight();

        // If image is already opaque and has reasonable background contrast, return original
        if (!src.getColorModel().hasAlpha()) {
            return pngBytes;
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            // white background
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            // draw source over white
            g.drawImage(src, 0, 0, null);

            // compute bounding box of non-transparent pixels
            int minX = w, minY = h, maxX = -1, maxY = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = src.getRGB(x, y);
                    int a = (argb >>> 24) & 0xff;
                    if (a > 16) { // consider nearly-transparent as transparent
                        if (x < minX) minX = x;
                        if (y < minY) minY = y;
                        if (x > maxX) maxX = x;
                        if (y > maxY) maxY = y;
                    }
                }
            }

            if (maxX >= 0) {
                // draw a subtle border around content to make stage boxes visible on white
                int pad = Math.max(2, Math.min(12, Math.max(w, h) / 200));
                int bx = Math.max(0, minX - pad);
                int by = Math.max(0, minY - pad);
                int bw = Math.min(w - 1, maxX + pad) - bx + 1;
                int bh = Math.min(h - 1, maxY + pad) - by + 1;

                java.awt.BasicStroke stroke = new java.awt.BasicStroke(Math.max(1f, Math.min(3f, Math.max(w, h) / 500f)));
                g.setStroke(stroke);
                g.setColor(new java.awt.Color(0, 0, 0, 48)); // faint shadow
                g.drawRect(bx + 1, by + 1, bw, bh);
                g.setColor(new java.awt.Color(0, 0, 0, 96)); // darker edge
                g.drawRect(bx, by, bw, bh);
            }
        } finally {
            g.dispose();
        }

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ImageIO.write(out, "png", baos);
        return baos.toByteArray();
    }

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

    // Return true when the image appears visually empty (mostly white/blank).
    private static boolean isMostlyBlank(BufferedImage img) {
        if (img == null) return true;
        final int w = img.getWidth();
        final int h = img.getHeight();
        long total = (long) w * h;
        long blank = 0;
        // sample pixels if image is large
        int stepX = Math.max(1, w / 200);
        int stepY = Math.max(1, h / 120);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = (rgb) & 0xff;
                // treat near-white as blank
                if (r > 240 && g > 240 && b > 240) blank++;
            }
        }
        double ratio = (double) blank / ((w / stepX) * (h / stepY));
        return ratio > 0.98d;
    }

    // Create a DMN placeholder PNG summarising the parsed DMN model (decision name, input/output/rule counts)
    private static byte[] createDmnPlaceholder(Object dmnModel, int width, int height) throws IOException {
        String title = "DMN";
        List<String> lines = new ArrayList<>();
        try {
            java.lang.reflect.Method getDecisions = dmnModel.getClass().getMethod("getDecisions");
            @SuppressWarnings("unchecked")
            List<Object> decisions = (List<Object>) getDecisions.invoke(dmnModel);
            if (decisions != null && !decisions.isEmpty()) {
                Object dec = decisions.get(0);
                try {
                    java.lang.reflect.Method getName = dec.getClass().getMethod("getName");
                    Object nm = getName.invoke(dec);
                    if (nm != null) title = nm.toString();
                } catch (NoSuchMethodException ignore) {}

                try {
                    java.lang.reflect.Method getDecisionTable = dec.getClass().getMethod("getDecisionTable");
                    Object table = getDecisionTable.invoke(dec);
                    if (table != null) {
                        try {
                            java.lang.reflect.Method getInputs = table.getClass().getMethod("getInputs");
                            java.lang.reflect.Method getOutputs = table.getClass().getMethod("getOutputs");
                            java.lang.reflect.Method getRules = table.getClass().getMethod("getRules");
                            @SuppressWarnings("unchecked")
                            List<Object> inputs = (List<Object>) getInputs.invoke(table);
                            @SuppressWarnings("unchecked")
                            List<Object> outputs = (List<Object>) getOutputs.invoke(table);
                            @SuppressWarnings("unchecked")
                            List<Object> rules = (List<Object>) getRules.invoke(table);
                            lines.add(String.format("inputs: %d", inputs == null ? 0 : inputs.size()));
                            lines.add(String.format("outputs: %d", outputs == null ? 0 : outputs.size()));
                            lines.add(String.format("rules: %d", rules == null ? 0 : rules.size()));

                            // add first few input names for context
                            if (inputs != null && !inputs.isEmpty()) {
                                StringBuilder sb = new StringBuilder();
                                sb.append("input(s): ");
                                int shown = Math.min(3, inputs.size());
                                for (int i = 0; i < shown; i++) {
                                    try {
                                        java.lang.reflect.Method inName = inputs.get(i).getClass().getMethod("getInputExpression");
                                        Object ie = inName.invoke(inputs.get(i));
                                        java.lang.reflect.Method textM = ie.getClass().getMethod("getText");
                                        Object txt = textM.invoke(ie);
                                        sb.append(txt == null ? "?" : txt.toString());
                                    } catch (Throwable ex) {
                                        // best-effort
                                        sb.append("?");
                                    }
                                    if (i < shown - 1) sb.append(", ");
                                }
                                lines.add(sb.toString());
                            }
                        } catch (NoSuchMethodException ignored) {
                            // best-effort; skip
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                    // skip
                }
            }
        } catch (NoSuchMethodException ignored) {
            // dmnModel does not expose expected methods; fallback to generic
        } catch (Throwable t) {
            // swallow — placeholder should never fail the renderer
        }

        if (lines.isEmpty()) lines.add("(no decision table diagram available)");

        int w = Math.max(600, width);
        int h = Math.max(360, height);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(java.awt.Color.DARK_GRAY);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, Math.max(18, w/32)));
            int y = 60;
            g.drawString(title, 40, y);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, Math.max(12, w/80)));
            y += 30;
            for (String ln : lines) {
                g.drawString(ln, 44, y);
                y += 22;
            }

            // faint box and hint
            g.setColor(new java.awt.Color(0,0,0,40));
            g.drawRect(20, 30, w - 44, Math.min(h - 80, y - 20));
            g.setColor(new java.awt.Color(120,120,120));
            String hint = "Note: source DMN contains no DRD/DI — showing a summary instead";
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.ITALIC, Math.max(10, w/120)));
            g.drawString(hint, 40, h - 28);
        } finally {
            g.dispose();
        }
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static byte[] createSimplePlaceholderPng(String title, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0,0,w,h);
            g.setColor(java.awt.Color.BLACK);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 28));
            g.drawString(title, 40, 80);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 14));
            g.drawString("(renderer could not produce a detailed DRD diagram)", 40, 120);
            g.drawRect(20, 40, w - 60, h - 120);
        } finally {
            g.dispose();
        }
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
