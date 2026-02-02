package vn.com.fecredit.flowable.exposer.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
            byte[] tableBytes = null; // additional artifact when DMN decision table is present
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

                    // If the source DMN contains an inline <decisionTable> prefer our
                    // XML-based decision-table renderer — Flowable's DRD generator
                    // frequently returns an effectively-empty diagram when no DI is present.
                    String xmlLower = new String(xmlBytes, java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
                    if (xmlLower.contains("<decisiontable")) {
                        tableBytes = createDmnDecisionTableImageFromXml(xmlBytes, 1000, 600);
                        if (tableBytes != null && tableBytes.length > 1024) {
                            imgBytes = tableBytes;
                            // keep tableBytes so we also write a dedicated artifact later
                        } else {
                            // otherwise fall through to the DRD generator as a safety net
                        }
                    }

                    if (imgBytes == null) {
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
                                // Prefer a rendered decision-table layout when a DecisionTable is present
                                byte[] tableImg = createDmnDecisionTableImage(dmnModel, w, h);
                                // If reflective extraction failed, fall back to XML-based extraction
                                if ((tableImg == null || tableImg.length < 1024) && xmlBytes != null) {
                                    byte[] xmlBased = createDmnDecisionTableImageFromXml(xmlBytes, w, h);
                                    if (xmlBased != null && xmlBased.length > 1024) tableImg = xmlBased;
                                }
                                if (tableImg != null && tableImg.length > 1024) {
                                    imgBytes = tableImg;
                                    tableBytes = tableImg; // preserve for separate artifact
                                } else {
                                    imgBytes = createDmnPlaceholder(dmnModel, w, h);
                                }
                            }
                            // safety net: if bytes are unexpectedly tiny, replace with a deterministic placeholder
                            if (imgBytes == null || imgBytes.length < 1024) {
                                imgBytes = createSimplePlaceholderPng("DMN summary", 1000, 600);
                            }
                        } catch (Throwable _t) {
                            // non-fatal: fall back to whatever the generator returned (or let downstream handle empty)
                        }
                    }

                    // If we parsed a decision table but did not produce a specialized table image above,
                    // still attempt to generate a tableBytes artifact so callers can inspect the tabular data.
                    if (tableBytes == null && xmlBytes != null && xmlLower.contains("<decisiontable")) {
                        try {
                            tableBytes = createDmnDecisionTableImageFromXml(xmlBytes, 1000, 600);
                        } catch (Throwable ignore) {
                            tableBytes = null;
                        }
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

            // Write additional DMN decision-table artifact when available (keeps existing PNG unchanged).
            if (tableBytes != null && tableBytes.length > 1024) {
                String baseName = outputFile.getFileName().toString();
                String tableName = baseName.replaceAll("(?i)\\.png$", "") + ".table.png";
                Path tableOut = outputFile.getParent().resolve(tableName);
                try (FileOutputStream tf = new FileOutputStream(tableOut.toFile())) {
                    tf.write(enhanceThumbnail(tableBytes));
                } catch (Throwable ignored) {
                    // non-fatal — main artifact already written
                }
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

    // Attempt to render a DecisionTable as a readable tabular PNG when the DMN DI/DRD
    // is not present or Flowable's image generator returns an empty image.
    // Returns null when no decision table could be rendered.
    private static byte[] createDmnDecisionTableImage(Object dmnModel, int width, int height) throws IOException {
        try {
            java.lang.reflect.Method getDecisions = dmnModel.getClass().getMethod("getDecisions");
            @SuppressWarnings("unchecked")
            List<Object> decisions = (List<Object>) getDecisions.invoke(dmnModel);
            if (decisions == null || decisions.isEmpty()) return null;
            Object dec = decisions.get(0);
            java.lang.reflect.Method getDecisionTable = dec.getClass().getMethod("getDecisionTable");
            Object table = getDecisionTable.invoke(dec);
            if (table == null) return null;

            // extract inputs, outputs, rules
            java.lang.reflect.Method getInputs = table.getClass().getMethod("getInputs");
            java.lang.reflect.Method getOutputs = table.getClass().getMethod("getOutputs");
            java.lang.reflect.Method getRules = table.getClass().getMethod("getRules");
            @SuppressWarnings("unchecked") List<Object> inputs = (List<Object>) getInputs.invoke(table);
            @SuppressWarnings("unchecked") List<Object> outputs = (List<Object>) getOutputs.invoke(table);
            @SuppressWarnings("unchecked") List<Object> rules = (List<Object>) getRules.invoke(table);
            if ((inputs == null || inputs.isEmpty()) && (outputs == null || outputs.isEmpty())) return null;

            // build string matrix for header + rows
            int inCount = inputs == null ? 0 : inputs.size();
            int outCount = outputs == null ? 0 : outputs.size();
            int colCount = inCount + outCount;
            int rowCount = rules == null ? 0 : rules.size();
            if (colCount == 0 || rowCount == 0) return null;

            // limit rows/cols to keep image readable
            final int MAX_COLS = 8;
            final int MAX_ROWS = 20;
            int renderCols = Math.min(colCount, MAX_COLS);
            int renderRows = Math.min(rowCount, MAX_ROWS);

            // prepare headers
            String[] headers = new String[renderCols];
            for (int i = 0; i < renderCols; i++) {
                if (i < inCount) {
                    try {
                        java.lang.reflect.Method ie = inputs.get(i).getClass().getMethod("getInputExpression");
                        Object expr = ie.invoke(inputs.get(i));
                        java.lang.reflect.Method txtM = expr.getClass().getMethod("getText");
                        Object txt = txtM.invoke(expr);
                        headers[i] = txt == null ? "in" + (i+1) : txt.toString();
                    } catch (Throwable t) { headers[i] = "in" + (i+1); }
                } else {
                    int oi = i - inCount;
                    if (oi < outputs.size()) {
                        try {
                            java.lang.reflect.Method nameM = outputs.get(oi).getClass().getMethod("getName");
                            Object nm = nameM.invoke(outputs.get(oi));
                            headers[i] = nm == null ? "out" + (oi+1) : nm.toString();
                        } catch (Throwable t) { headers[i] = "out" + (oi+1); }
                    } else {
                        headers[i] = "col" + (i+1);
                    }
                }
            }

            // gather row values
            String[][] cells = new String[renderRows][renderCols];
            for (int r = 0; r < renderRows; r++) {
                Object rule = rules.get(r);
                // input entries
                try {
                    java.lang.reflect.Method getInputEntries = rule.getClass().getMethod("getInputEntries");
                    java.lang.reflect.Method getOutputEntries = rule.getClass().getMethod("getOutputEntries");
                    @SuppressWarnings("unchecked") List<Object> inEls = (List<Object>) getInputEntries.invoke(rule);
                    @SuppressWarnings("unchecked") List<Object> outEls = (List<Object>) getOutputEntries.invoke(rule);
                    for (int c = 0; c < renderCols; c++) {
                        if (c < inCount) {
                            int idx = c;
                            if (inEls != null && idx < inEls.size() && inEls.get(idx) != null) {
                                try {
                                    java.lang.reflect.Method txtM = inEls.get(idx).getClass().getMethod("getText");
                                    Object txt = txtM.invoke(inEls.get(idx));
                                    cells[r][c] = txt == null ? "-" : txt.toString();
                                } catch (Throwable t) { cells[r][c] = "-"; }
                            } else {
                                cells[r][c] = "-";
                            }
                        } else {
                            int oi = c - inCount;
                            if (outEls != null && oi < outEls.size() && outEls.get(oi) != null) {
                                try {
                                    java.lang.reflect.Method txtM = outEls.get(oi).getClass().getMethod("getText");
                                    Object txt = txtM.invoke(outEls.get(oi));
                                    cells[r][c] = txt == null ? "-" : txt.toString();
                                } catch (Throwable t) { cells[r][c] = "-"; }
                            } else {
                                cells[r][c] = "-";
                            }
                        }
                    }
                } catch (NoSuchMethodException nsme) {
                    // can't extract entries — fall back
                    for (int c = 0; c < renderCols; c++) cells[r][c] = "?";
                }
            }

            // layout: compute column widths using font metrics
            int w = Math.max(600, width);
            int h = Math.max(360, height);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            try {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0,0,w,h);
                g.setColor(java.awt.Color.DARK_GRAY);
                int padding = 12;
                int tableX = 24;
                int tableY = 48;
                int availW = w - tableX - 24;

                java.awt.Font headerFont = new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14);
                java.awt.Font cellFont = new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 13);
                g.setFont(cellFont);
                java.awt.FontMetrics fmCell = g.getFontMetrics(cellFont);
                java.awt.FontMetrics fmHeader = g.getFontMetrics(headerFont);

                int[] colW = new int[renderCols];
                int minCol = 60;
                for (int c = 0; c < renderCols; c++) {
                    int mw = fmHeader.stringWidth(headers[c]) + padding * 2;
                    for (int r = 0; r < renderRows; r++) {
                        int sw = fmCell.stringWidth(truncate(cells[r][c], 60));
                        if (sw + padding * 2 > mw) mw = sw + padding * 2;
                    }
                    colW[c] = Math.max(minCol, mw);
                }
                // if total too wide, scale columns proportionally
                int totalW = 0; for (int x : colW) totalW += x;
                if (totalW > availW) {
                    double scale = (double) availW / (double) totalW;
                    for (int c = 0; c < colW.length; c++) colW[c] = Math.max(40, (int)(colW[c] * scale));
                    totalW = 0; for (int x : colW) totalW += x;
                }

                int rowH = Math.max(20, fmCell.getHeight() + 8);
                int headerH = Math.max(24, fmHeader.getHeight() + 8);

                // draw title (use header context when decision name is unavailable)
                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 18));
                String title = (headers != null && headers.length > 0 && headers[0] != null && !headers[0].isBlank())
                    ? headers[0] + " — decision table" : "Decision Table";
                 g.setColor(java.awt.Color.BLACK);
                 g.drawString(title, tableX, 30);

                // draw header background
                int cx = tableX;
                int cy = tableY;
                g.setColor(new java.awt.Color(240,240,250));
                g.fillRect(cx, cy, totalW, headerH);
                g.setColor(java.awt.Color.DARK_GRAY);
                g.setFont(headerFont);
                for (int c = 0; c < renderCols; c++) {
                    int cw = colW[c];
                    int tx = cx + 6;
                    int ty = cy + headerH - fmHeader.getDescent() - 6;
                    g.drawString(elide(headers[c], 24), tx, ty);
                    cx += cw;
                }

                // draw rows
                g.setFont(cellFont);
                cy += headerH;
                for (int r = 0; r < renderRows; r++) {
                    cx = tableX;
                    // alternate row background to improve readability (zebra striping)
                    if ((r & 1) == 0) {
                        g.setColor(new java.awt.Color(255,255,255));
                    } else {
                        // subtle tint that remains printer-friendly and accessible
                        g.setColor(new java.awt.Color(250,250,255));
                    }
                    g.fillRect(cx, cy, totalW, fmCell.getHeight() + 8);
                    // restore text color before drawing cell content
                    g.setColor(java.awt.Color.DARK_GRAY);
                    for (int c = 0; c < renderCols; c++) {
                        int tx = cx + 6;
                        int ty = cy + fmCell.getAscent();
                        g.drawString(elide(cells[r][c], 40), tx, ty);
                        cx += colW[c];
                    }
                    cy += fmCell.getHeight() + 8;
                }

                // draw grid lines (vertical + horizontal) to emphasise cell boundaries
                g.setColor(new java.awt.Color(200,200,220));
                int gx = tableX;
                int gy = tableY;
                for (int c = 0; c <= renderCols; c++) {
                    g.drawLine(gx, gy, gx, gy + (fmHeader.getHeight() + 8) + (fmCell.getHeight() + 8) * renderRows);
                    if (c < renderCols) gx += colW[c];
                }
                for (int r = 0; r <= renderRows; r++) {
                    int yoff = tableY + (r == 0 ? 0 : (fmHeader.getHeight() + 8)) + r * (fmCell.getHeight() + 8);
                    g.drawLine(tableX, yoff, tableX + totalW, yoff);
                }

                // footer hint
                g.setColor(new java.awt.Color(100,100,120));
                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
                g.drawString("Rendered from DMN decision table (summary)", tableX, Math.min(h - 12, cy + 20));
            } finally {
                g.dispose();
            }

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (NoSuchMethodException nsme) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // XML-based extractor: parse DMN XML and render a decision-table PNG. Best-effort and non-throwing.
    private static byte[] createDmnDecisionTableImageFromXml(byte[] xmlBytes, int width, int height) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xmlBytes));

            // find the first decisionTable element
            org.w3c.dom.NodeList tables = doc.getElementsByTagNameNS("http://www.omg.org/spec/DMN/20151101/dmn.xsd", "decisionTable");
            if (tables.getLength() == 0) {
                // try without namespace (some modelers omit it)
                tables = doc.getElementsByTagName("decisionTable");
                if (tables.getLength() == 0) return null;
            }
            org.w3c.dom.Element table = (org.w3c.dom.Element) tables.item(0);

            // inputs
            java.util.List<String> inputs = new ArrayList<>();
            org.w3c.dom.NodeList inputEls = table.getElementsByTagName("input");
            for (int i = 0; i < inputEls.getLength(); i++) {
                org.w3c.dom.Element in = (org.w3c.dom.Element) inputEls.item(i);
                String label = in.getAttribute("label");
                if (label == null || label.isBlank()) {
                    org.w3c.dom.NodeList exprs = in.getElementsByTagName("inputExpression");
                    if (exprs.getLength() > 0) {
                        org.w3c.dom.Element ie = (org.w3c.dom.Element) exprs.item(0);
                        org.w3c.dom.NodeList texts = ie.getElementsByTagName("text");
                        if (texts.getLength() > 0) label = texts.item(0).getTextContent();
                    }
                }
                inputs.add(label == null || label.isBlank() ? ("in" + (i+1)) : label.trim());
            }

            // outputs
            java.util.List<String> outputs = new ArrayList<>();
            org.w3c.dom.NodeList outputEls = table.getElementsByTagName("output");
            for (int i = 0; i < outputEls.getLength(); i++) {
                org.w3c.dom.Element out = (org.w3c.dom.Element) outputEls.item(i);
                String name = out.getAttribute("name");
                outputs.add(name == null || name.isBlank() ? ("out" + (i+1)) : name.trim());
            }

            // rules
            java.util.List<java.util.List<String>> rows = new ArrayList<>();
            org.w3c.dom.NodeList ruleEls = table.getElementsByTagName("rule");
            for (int r = 0; r < ruleEls.getLength(); r++) {
                org.w3c.dom.Element rule = (org.w3c.dom.Element) ruleEls.item(r);
                java.util.List<String> row = new ArrayList<>();
                org.w3c.dom.NodeList inEntries = rule.getElementsByTagName("inputEntry");
                for (int i = 0; i < inputs.size(); i++) {
                    if (i < inEntries.getLength()) {
                        String txt = inEntries.item(i).getTextContent();
                        row.add(txt == null || txt.isBlank() ? "-" : txt.trim());
                    } else {
                        row.add("-");
                    }
                }
                org.w3c.dom.NodeList outEntries = rule.getElementsByTagName("outputEntry");
                for (int i = 0; i < outputs.size(); i++) {
                    if (i < outEntries.getLength()) {
                        String txt = outEntries.item(i).getTextContent();
                        row.add(txt == null || txt.isBlank() ? "-" : txt.trim());
                    } else {
                        row.add("-");
                    }
                }
                rows.add(row);
            }

            if (rows.isEmpty()) return null;

            // reuse drawing logic: build headers and a cells matrix then paint similar to createDmnDecisionTableImage
            int inCount = inputs.size();
            int outCount = outputs.size();
            int colCount = inCount + outCount;
            int rowCount = rows.size();

            final int MAX_COLS = 8;
            final int MAX_ROWS = 20;
            int renderCols = Math.min(colCount, MAX_COLS);
            int renderRows = Math.min(rowCount, MAX_ROWS);

            String[] headers = new String[renderCols];
            for (int i = 0; i < renderCols; i++) {
                if (i < inCount) headers[i] = inputs.get(i);
                else headers[i] = outputs.get(i - inCount);
            }

            String[][] cells = new String[renderRows][renderCols];
            for (int r = 0; r < renderRows; r++) {
                java.util.List<String> src = rows.get(r);
                for (int c = 0; c < renderCols; c++) {
                    cells[r][c] = c < src.size() ? src.get(c) : "-";
                }
            }

            // now draw (similar to createDmnDecisionTableImage)
            int w = Math.max(600, width);
            int h = Math.max(360, height);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            try {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0,0,w,h);
                g.setColor(java.awt.Color.DARK_GRAY);
                int padding = 12;
                int tableX = 24;
                int tableY = 48;
                int availW = w - tableX - 24;

                java.awt.Font headerFont = new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14);
                java.awt.Font cellFont = new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 13);
                g.setFont(cellFont);
                java.awt.FontMetrics fmCell = g.getFontMetrics(cellFont);
                java.awt.FontMetrics fmHeader = g.getFontMetrics(headerFont);

                int[] colW = new int[renderCols];
                int minCol = 60;
                for (int c = 0; c < renderCols; c++) {
                    int mw = fmHeader.stringWidth(headers[c]) + padding * 2;
                    for (int r = 0; r < renderRows; r++) {
                        int sw = fmCell.stringWidth(truncate(cells[r][c], 60));
                        if (sw + padding * 2 > mw) mw = sw + padding * 2;
                    }
                    colW[c] = Math.max(minCol, mw);
                }
                int totalW = 0; for (int x : colW) totalW += x;
                if (totalW > availW) {
                    double scale = (double) availW / (double) totalW;
                    for (int c = 0; c < colW.length; c++) colW[c] = Math.max(40, (int)(colW[c] * scale));
                    totalW = 0; for (int x : colW) totalW += x;
                }

                // title
                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 18));
                g.drawString("Decision Table", tableX, 30);

                // headers + rows
                int cx = tableX, cy = tableY;
                g.setColor(new java.awt.Color(240,240,250));
                g.fillRect(cx, cy, totalW, fmHeader.getHeight() + 8);
                g.setColor(java.awt.Color.DARK_GRAY);
                g.setFont(headerFont);
                for (int c = 0; c < renderCols; c++) {
                    int tx = cx + 6;
                    int ty = cy + fmHeader.getAscent();
                    g.drawString(elide(headers[c], 24), tx, ty);
                    cx += colW[c];
                }
                cy += fmHeader.getHeight() + 8;
                g.setFont(cellFont);
                for (int r = 0; r < renderRows; r++) {
                    cx = tableX;
                    // alternate row background to improve readability (zebra striping)
                    if ((r & 1) == 0) {
                        g.setColor(new java.awt.Color(255,255,255));
                    } else {
                        // subtle tint that remains printer-friendly and accessible
                        g.setColor(new java.awt.Color(250,250,255));
                    }
                    g.fillRect(cx, cy, totalW, fmCell.getHeight() + 8);
                    // restore text color before drawing cell content
                    g.setColor(java.awt.Color.DARK_GRAY);
                    for (int c = 0; c < renderCols; c++) {
                        int tx = cx + 6;
                        int ty = cy + fmCell.getAscent();
                        g.drawString(elide(cells[r][c], 40), tx, ty);
                        cx += colW[c];
                    }
                    cy += fmCell.getHeight() + 8;
                }

                // draw grid lines (vertical + horizontal) to emphasise cell boundaries
                g.setColor(new java.awt.Color(200,200,220));
                int gx = tableX;
                int gy = tableY;
                for (int c = 0; c <= renderCols; c++) {
                    g.drawLine(gx, gy, gx, gy + (fmHeader.getHeight() + 8) + (fmCell.getHeight() + 8) * renderRows);
                    if (c < renderCols) gx += colW[c];
                }
                for (int r = 0; r <= renderRows; r++) {
                    int yoff = tableY + (r == 0 ? 0 : (fmHeader.getHeight() + 8)) + r * (fmCell.getHeight() + 8);
                    g.drawLine(tableX, yoff, tableX + totalW, yoff);
                }

                // footer hint
                g.setColor(new java.awt.Color(100,100,120));
                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
                g.drawString("Rendered from DMN decision table (summary)", tableX, Math.min(h - 12, cy + 20));
            } finally {
                g.dispose();
            }

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception ex) {
            return null;
        }
    }

    private static byte[] createSimplePlaceholderPng(String title, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0,0,w,h);
            g.setColor(java.awt.Color.BLACK);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, Math.max(20, w/40)));
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static String elide(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\n", " ").trim();
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
