package vn.com.fecredit.flowable.exposer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.List;
import java.util.Map;

/**
 * Annotates JSON-like maps with minimal @class markers using resolved metadata.
 * Refactored to use MetadataLookup (typed) and to keep methods short and focused.
 */
@Component
public class MetadataAnnotator {

    private static final Logger log = LoggerFactory.getLogger(MetadataAnnotator.class);

    private final MetadataLookup lookup;

    public MetadataAnnotator(MetadataLookup lookup) {
        this.lookup = lookup;
    }

    public void annotate(Map<String, Object> root, String rootClass) {
        if (root == null || rootClass == null) return;
        try {
            root.putIfAbsent("@class", rootClass);
            MetadataDefinition md = lookup.resolve(rootClass);
            if (md == null) return;
            List<MetadataDefinition.FieldDef> fields = lookup.fieldsOf(md);
            if (fields == null) return;

            for (MetadataDefinition.FieldDef fd : fields) {
                processFieldOnRoot(root, fd);
            }

            inferMissingNestedClasses(root);
        } catch (Exception ex) {
            log.debug("MetadataAnnotator failed", ex);
        }
    }

    private void processFieldOnRoot(Map<String, Object> root, MetadataDefinition.FieldDef fd) {
        if (fd == null) return;
        String name = MetadataLookup.safeName(fd);
        if (name == null) return;
        Object val = root.get(name);
        if (val == null) return;

        String className = MetadataLookup.primaryClassFor(fd);
        String elementClass = MetadataLookup.elementClassFor(fd);

        if (className != null && val instanceof Map) {
            annotateMap((Map<String, Object>) val, className);
        } else if (elementClass != null && val instanceof List) {
            annotateList((List<Object>) val, elementClass);
        } else if (elementClass != null && val instanceof Map) {
            annotateValueMap((Map<String, Object>) val, elementClass);
        } else if (val instanceof Map) {
            annotateByClass((Map<String, Object>) val, name);
        }
    }

    private void annotateMap(Map<String, Object> m, String className) {
        if (m.putIfAbsent("@class", className) == null) {
            log.info("MetadataAnnotator: annotated map as @class='{}'", className);
        }
        annotateByClass(m, className);
    }

    private void annotateList(List<Object> list, String elementClass) {
        for (Object it : list) {
            if (it instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> im = (Map<String, Object>) it;
                im.putIfAbsent("@class", elementClass);
                annotateByClass(im, elementClass);
            }
        }
    }

    private void annotateValueMap(Map<String, Object> map, String elementClass) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object it = e.getValue();
            if (it instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> im = (Map<String, Object>) it;
                im.putIfAbsent("@class", elementClass);
                annotateByClass(im, elementClass);
            }
        }
    }

    private void inferMissingNestedClasses(Map<String, Object> root) {
        for (Map.Entry<String, Object> e : root.entrySet()) {
            Object v = e.getValue();
            if (!(v instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> child = (Map<String, Object>) v;
            if (child.containsKey("@class")) continue;
            String k = e.getKey();
            if (k == null || k.isBlank()) continue;
            String cand = Character.toUpperCase(k.charAt(0)) + k.substring(1);
            MetadataDefinition md2 = lookup.resolve(cand);
            if (md2 != null) {
                if (child.putIfAbsent("@class", cand) == null) {
                    log.info("MetadataAnnotator: inferred @class='{}' for nested field '{}'", cand, k);
                }
                annotateByClass(child, cand);
            }
        }
    }

    private void annotateByClass(Map<String, Object> node, String className) {
        if (node == null || className == null) return;
        String inferred = className;
        if (!inferred.isBlank() && !Character.isUpperCase(inferred.charAt(0))) {
            inferred = Character.toUpperCase(inferred.charAt(0)) + inferred.substring(1);
        }
        node.putIfAbsent("@class", inferred);

        MetadataDefinition md = lookup.resolve(className);
        if (md == null) return;
        List<MetadataDefinition.FieldDef> fields = lookup.fieldsOf(md);
        if (fields == null) return;

        for (MetadataDefinition.FieldDef fd : fields) {
            if (fd == null) continue;
            String name = MetadataLookup.safeName(fd);
            if (name == null) continue;
            Object val = node.get(name);
            if (val == null) continue;
            String childClass = MetadataLookup.primaryClassFor(fd);
            String elementClass = MetadataLookup.elementClassFor(fd);
            if (childClass != null && val instanceof Map) {
                annotateMap((Map<String, Object>) val, childClass);
            } else if (elementClass != null && val instanceof List) {
                annotateList((List<Object>) val, elementClass);
            } else if (elementClass != null && val instanceof Map) {
                annotateValueMap((Map<String, Object>) val, elementClass);
            } else if (val instanceof Map) {
                annotateByClass((Map<String, Object>) val, name);
            }
        }
    }
}
