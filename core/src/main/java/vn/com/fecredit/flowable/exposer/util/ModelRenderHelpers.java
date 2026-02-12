package vn.com.fecredit.flowable.exposer.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

/**
 * Renderer delegation extracted from ModelValidatorRenderer to reduce file size.
 */
public final class ModelRenderHelpers {
    private ModelRenderHelpers() {}

    public static Path renderToPng(Path xml, Path outputFile) throws IOException {
        try {
            ModelValidatorRenderer.ModelType type = ModelValidatorRenderer.detectType(xml);
            byte[] imgBytes = null;
            byte[] tableBytes = null;

            switch (type) {
                case BPMN: {
                    byte[] xmlBytes = Files.readAllBytes(xml);
                    XMLInputFactory xif = XMLInputFactory.newInstance();
                    XMLStreamReader xtr = xif.createXMLStreamReader(new ByteArrayInputStream(xmlBytes));
                    Object bpmnModel = ModelConverterHelpers.tryConvertForType(ModelValidatorRenderer.ModelType.BPMN, xmlBytes, xtr);
                    if (bpmnModel == null) throw new IllegalStateException("BPMN converter not available on classpath");
                    Object gen = Class.forName("org.flowable.image.impl.DefaultProcessDiagramGenerator").getDeclaredConstructor().newInstance();
                    java.lang.reflect.Method m = gen.getClass().getMethod("generateDiagram", org.flowable.bpmn.model.BpmnModel.class, String.class, java.util.List.class, java.util.List.class, String.class, String.class, String.class, ClassLoader.class, double.class, boolean.class);
                    try (InputStream is = (InputStream) m.invoke(gen, bpmnModel, "png", Collections.emptyList(), Collections.emptyList(), "Arial", "Arial", "Arial", ModelRenderHelpers.class.getClassLoader(), 1.0d, Boolean.FALSE)) {
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
                    Object cmmnModel = ModelConverterHelpers.tryConvertForType(ModelValidatorRenderer.ModelType.CMMN, xmlBytes, xtr);
                    if (cmmnModel == null) throw new IllegalStateException("CMMN converter not available on classpath");
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
                    Object dmnModel = ModelConverterHelpers.tryConvertForType(ModelValidatorRenderer.ModelType.DMN, xmlBytes, xtr);
                    if (dmnModel == null) throw new IllegalStateException("DMN converter not available on classpath");

                    String xmlLower = new String(xmlBytes, java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
                    if (xmlLower.contains("<decisiontable")) {
                        tableBytes = ModelDmnHelpers.createDmnDecisionTableImageFromXml(xmlBytes, 1000, 600);
                        if (tableBytes != null && tableBytes.length > 1024) {
                            imgBytes = tableBytes;
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

                        try {
                            BufferedImage produced = null;
                            if (imgBytes != null && imgBytes.length > 0) produced = ImageIO.read(new ByteArrayInputStream(imgBytes));
                            if (produced == null || ModelImageHelpers.isMostlyBlank(produced)) {
                                int w = (produced != null) ? produced.getWidth() : 1000;
                                int h = (produced != null) ? produced.getHeight() : 600;
                                byte[] tableImg = ModelDmnHelpers.createDmnDecisionTableImage(dmnModel, w, h);
                                if ((tableImg == null || tableImg.length < 1024) && xmlBytes != null) {
                                    byte[] xmlBased = ModelDmnHelpers.createDmnDecisionTableImageFromXml(xmlBytes, w, h);
                                    if (xmlBased != null && xmlBased.length > 1024) tableImg = xmlBased;
                                }
                                if (tableImg != null && tableImg.length > 1024) {
                                    imgBytes = tableImg;
                                    tableBytes = tableImg;
                                } else {
                                    imgBytes = ModelDmnHelpers.createDmnPlaceholder(dmnModel, w, h);
                                }
                            }
                            if (imgBytes == null || imgBytes.length < 1024) {
                                imgBytes = ModelDmnRenderer.renderPlaceholder("DMN summary", java.util.List.of("(renderer could not produce a detailed DRD diagram)"), 1000, 600);
                            }
                        } catch (Throwable _t) {
                            // non-fatal
                        }
                    }

                    if (tableBytes == null && xmlBytes != null && xmlLower.contains("<decisiontable")) {
                        try { tableBytes = ModelDmnHelpers.createDmnDecisionTableImageFromXml(xmlBytes, 1000, 600); } catch (Throwable ignore) { tableBytes = null; }
                    }

                    break;
                }
                default:
                    throw new IllegalStateException("Unsupported model type for rendering: " + ModelValidatorRenderer.detectType(xml));
            }

            if (imgBytes == null || imgBytes.length == 0) throw new IOException("Renderer returned no image bytes");

            imgBytes = ModelImageHelpers.enhanceThumbnail(imgBytes);

            Files.createDirectories(outputFile.getParent());
            try (FileOutputStream fos = new FileOutputStream(outputFile.toFile())) { fos.write(imgBytes); }

            if (tableBytes != null && tableBytes.length > 1024) {
                String baseName = outputFile.getFileName().toString();
                String tableName = baseName.replaceAll("(?i)\\.png$", "") + ".table.png";
                Path tableOut = outputFile.getParent().resolve(tableName);
                try (FileOutputStream tf = new FileOutputStream(tableOut.toFile())) { tf.write(ModelImageHelpers.enhanceThumbnail(tableBytes)); } catch (Throwable ignored) { }
            }
            return outputFile;
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
