package vn.com.fecredit.flowable.exposer.util;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class ModelConverterHelpers {
    private ModelConverterHelpers() {}

    public static Object tryConvertForType(ModelValidatorRenderer.ModelType type, byte[] xmlBytes, XMLStreamReader xtr) throws Exception {
        String[] candidates;
        switch (type) {
            case CMMN:
                candidates = new String[]{"org.flowable.cmmn.converter.CmmnXMLConverter", "org.flowable.cmmn.converter.CmmnXmlConverter"};
                break;
            case BPMN:
                candidates = new String[]{"org.flowable.bpmn.converter.BpmnXMLConverter", "org.flowable.bpmn.converter.BpmnXmlConverter"};
                break;
            case DMN:
                candidates = new String[]{"org.flowable.dmn.xml.converter.DmnXMLConverter", "org.flowable.dmn.xml.converter.DmnXmlConverter", "org.flowable.dmn.converter.DmnXMLConverter"};
                break;
            default:
                return null;
        }
        for (String c : candidates) {
            try {
                Object m = tryConvertReflectively(c, xmlBytes, xtr);
                if (m != null) return m;
            } catch (ClassNotFoundException cnf) {
                // try next
            } catch (IllegalStateException ise) {
                throw ise;
            }
        }
        return null;
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
                            && method.getParameterTypes()[0].isAssignableFrom(InputStream.class)) {
                        try {
                            return method.invoke(conv, new ByteArrayInputStream(xmlBytes));
                        } catch (java.lang.reflect.InvocationTargetException ite) {
                            throw ite.getCause() != null ? new Exception(ite.getCause()) : ite;
                        }
                    }
                }
            }

            // 3) try to detect an InputStreamProvider parameter
            for (java.lang.reflect.Method method : convClass.getMethods()) {
                if (method.getName().startsWith("convertTo") && method.getParameterCount() == 1) {
                    Class<?> ptype = method.getParameterTypes()[0];
                    if (ptype.getSimpleName().toLowerCase().contains("inputstreamprovider") && xmlBytes != null) {
                        Object provider = java.lang.reflect.Proxy.newProxyInstance(ptype.getClassLoader(), new Class<?>[]{ptype}, (proxy, m, args) -> {
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

            StringBuilder sb = new StringBuilder();
            sb.append("Converter ").append(converterClassName).append(" present but no compatible convertTo* method found. Available methods:\n");
            for (java.lang.reflect.Method method : convClass.getMethods()) {
                if (method.getName().startsWith("convertTo")) sb.append("  ").append(method.toString()).append('\n');
            }
            throw new IllegalStateException(sb.toString());
        } catch (ClassNotFoundException cnf) {
            return null;
        }
    }

    public static Schema getSchemaForType(ModelValidatorRenderer.ModelType type) {
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
            if (sources.isEmpty()) return null;
            return sf.newSchema(sources.toArray(new StreamSource[0]));
        } catch (Exception e) {
            return null;
        }
    }

    private static void addIfFound(List<StreamSource> out, String resourcePath) {
        InputStream is = ModelValidatorRenderer.class.getResourceAsStream(resourcePath);
        if (is != null) out.add(new StreamSource(is));
    }
}
