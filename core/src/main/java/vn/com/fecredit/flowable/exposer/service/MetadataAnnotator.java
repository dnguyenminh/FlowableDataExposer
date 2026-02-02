package vn.com.fecredit.flowable.exposer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Performs runtime annotation of JSON-like maps using metadata definitions.
 *
 * <p>The annotator will add minimal {@code @class} markers and recurse into
 * nested objects/arrays so downstream components (JsonPath, indexer) can
 * reliably resolve class-scoped mappings. This is intentionally defensive
 * and uses reflection against {@code MetadataResolver} so it remains
 * compatible with test or trimmed classpaths.</p>
 */
@Component
public class MetadataAnnotator {

    private static final Logger log = LoggerFactory.getLogger(MetadataAnnotator.class);

    private final MetadataResolver resolver;

    public MetadataAnnotator(MetadataResolver resolver) {
        this.resolver = resolver;
    }

    public void annotate(Map<String, Object> root, String rootClass) {
        if (root == null || rootClass == null) return;
        try {
            Method resolve = null;
            try { resolve = resolver.getClass().getMethod("resolveForClass", String.class); } catch (NoSuchMethodException e) { return; }
            Object md = resolve.invoke(resolver, rootClass);
            if (md == null) return;

            Field fieldsField = null;
            try { fieldsField = md.getClass().getField("fields"); } catch (NoSuchFieldException e) { return; }
            Object fieldsObj = fieldsField.get(md);
            if (!(fieldsObj instanceof List)) return;
            @SuppressWarnings("unchecked") List<Object> fields = (List<Object>) fieldsObj;

            for (Object fd : fields) {
                if (fd == null) continue;
                String name = null; String className = null; String elementClass = null;
                try {
                    Field fn = fd.getClass().getField("name"); Object nv = fn.get(fd); if (nv != null) name = String.valueOf(nv);
                } catch (Exception ignored) {}
                try {
                    Field fc = fd.getClass().getField("className"); Object cv = fc.get(fd); if (cv != null) className = String.valueOf(cv);
                } catch (Exception ignored) {}
                try {
                    Field fe = fd.getClass().getField("elementClass"); Object ev = fe.get(fd); if (ev != null) elementClass = String.valueOf(ev);
                } catch (Exception ignored) {}

                if (name == null) continue;
                Object val = root.get(name);
                if (val == null) continue;
                if (className != null && val instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String,Object> m = (Map<String,Object>) val;
                    m.putIfAbsent("@class", className);
                    // attempt to recurse by resolving child class
                    annotateByClass(m, className);
                } else if (elementClass != null && val instanceof List) {
                    @SuppressWarnings("unchecked") List<Object> list = (List<Object>) val;
                    for (Object it : list) {
                        if (it instanceof Map) {
                            @SuppressWarnings("unchecked") Map<String,Object> im = (Map<String,Object>) it;
                            im.putIfAbsent("@class", elementClass);
                            annotateByClass(im, elementClass);
                        }
                    }
                } else if (val instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String,Object> m = (Map<String,Object>) val;
                    // no explicit class, attempt to recurse using field name as class key
                    annotateByClass(m, name);
                }
            }
        } catch (Exception ex) {
            log.debug("MetadataAnnotator failed", ex);
        }
    }

    private void annotateByClass(Map<String,Object> node, String className) {
        if (node == null || className == null) return;
        try {
            Method resolve = null;
            try { resolve = resolver.getClass().getMethod("resolveForClass", String.class); } catch (NoSuchMethodException e) { return; }
            Object md = resolve.invoke(resolver, className);
            if (md == null) return;
            Field fieldsField = null;
            try { fieldsField = md.getClass().getField("fields"); } catch (NoSuchFieldException e) { return; }
            Object fieldsObj = fieldsField.get(md);
            if (!(fieldsObj instanceof List)) return;
            @SuppressWarnings("unchecked") List<Object> fields = (List<Object>) fieldsObj;

            for (Object fd : fields) {
                if (fd == null) continue;
                String name = null; String childClass = null; String elementClass = null;
                try { Field fn = fd.getClass().getField("name"); Object nv = fn.get(fd); if (nv != null) name = String.valueOf(nv); } catch (Exception ignored) {}
                try { Field fc = fd.getClass().getField("className"); Object cv = fc.get(fd); if (cv != null) childClass = String.valueOf(cv); } catch (Exception ignored) {}
                try { Field fe = fd.getClass().getField("elementClass"); Object ev = fe.get(fd); if (ev != null) elementClass = String.valueOf(ev); } catch (Exception ignored) {}

                if (name == null) continue;
                Object val = node.get(name);
                if (val == null) continue;
                if (childClass != null && val instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String,Object> m = (Map<String,Object>) val;
                    m.putIfAbsent("@class", childClass);
                    annotateByClass(m, childClass);
                } else if (elementClass != null && val instanceof List) {
                    @SuppressWarnings("unchecked") List<Object> list = (List<Object>) val;
                    for (Object it : list) {
                        if (it instanceof Map) {
                            @SuppressWarnings("unchecked") Map<String,Object> im = (Map<String,Object>) it;
                            im.putIfAbsent("@class", elementClass);
                            annotateByClass(im, elementClass);
                        }
                    }
                } else if (val instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String,Object> m = (Map<String,Object>) val;
                    annotateByClass(m, name);
                }
            }
        } catch (Exception ex) {
            log.debug("annotateByClass failed", ex);
        }
    }
}
