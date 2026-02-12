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
            // Ensure the root object has a class marker so downstream consumers
            // can always rely on an anchor for resolver lookups.
            root.putIfAbsent("@class", rootClass);

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

                // flexible child class detection: prefer explicit className, then class, then type
                try {
                    Field fc = fd.getClass().getField("className"); Object cv = fc.get(fd); if (cv != null) className = String.valueOf(cv);
                } catch (Exception ignored) {}
                if (className == null) {
                    try { Field fc = fd.getClass().getField("class"); Object cv = fc.get(fd); if (cv != null) className = String.valueOf(cv); } catch (Exception ignored) {}
                }
                if (className == null) {
                    try { Field fc = fd.getClass().getField("type"); Object cv = fc.get(fd); if (cv != null) className = String.valueOf(cv); } catch (Exception ignored) {}
                }

                // flexible element class detection: explicit elementClass/elementType, or type + isArray
                try {
                    Field fe = fd.getClass().getField("elementClass"); Object ev = fe.get(fd); if (ev != null) elementClass = String.valueOf(ev);
                } catch (Exception ignored) {}
                if (elementClass == null) {
                    try { Field fe = fd.getClass().getField("elementType"); Object ev = fe.get(fd); if (ev != null) elementClass = String.valueOf(ev); } catch (Exception ignored) {}
                }
                if (elementClass == null) {
                    // fallback: if 'type' exists and 'isArray' is true, treat type as element class
                    try {
                        Field ft = fd.getClass().getField("type"); Object tv = ft.get(fd);
                        if (tv != null) {
                            boolean isArray = false;
                            try { Field fa = fd.getClass().getField("isArray"); Object av = fa.get(fd); if (av instanceof Boolean) isArray = (Boolean) av; } catch (Exception ignored) {}
                            try { Field fa = fd.getClass().getField("isList"); Object av = fa.get(fd); if (av instanceof Boolean) isArray = isArray || (Boolean) av; } catch (Exception ignored) {}
                            if (isArray) elementClass = String.valueOf(tv);
                        }
                    } catch (Exception ignored) {}
                }

                if (name == null) continue;
                Object val = root.get(name);
                if (val == null) continue;
                if (className != null && val instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String,Object> m = (Map<String,Object>) val;
                    if (m.putIfAbsent("@class", className) == null) {
                        log.info("MetadataAnnotator: annotated field '{}' as @class='{}' on root", name, className);
                    } else {
                        log.debug("MetadataAnnotator: field '{}' already had @class, skipping annotation", name);
                    }
                    // attempt to recurse by resolving child class
                    annotateByClass(m, className);
                } else if (elementClass != null && val instanceof List) {
                    @SuppressWarnings("unchecked") List<Object> list = (List<Object>) val;
                    for (Object it : list) {
                        if (it instanceof Map) {
                            @SuppressWarnings("unchecked") Map<String,Object> im = (Map<String,Object>) it;
                            if (im.putIfAbsent("@class", elementClass) == null) {
                                log.info("MetadataAnnotator: annotated map value for field '{}' as @class='{}'", name, elementClass);
                            }
                            annotateByClass(im, elementClass);
                        }
                    }
                } else if (elementClass != null && val instanceof Map) {
                    // support hashmap-like fields: map<string,object>
                    @SuppressWarnings("unchecked") Map<String,Object> map = (Map<String,Object>) val;
                    for (Map.Entry<String,Object> e : map.entrySet()) {
                        Object it = e.getValue();
                        if (it instanceof Map) {
                            @SuppressWarnings("unchecked") Map<String,Object> im = (Map<String,Object>) it;
                            if (im.putIfAbsent("@class", elementClass) == null) {
                                log.info("MetadataAnnotator: annotated list element for field '{}' as @class='{}'", name, elementClass);
                            }
                            annotateByClass(im, elementClass);
                        }
                    }
                } else if (val instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String,Object> m = (Map<String,Object>) val;
                    // no explicit class, attempt to recurse using field name as class key
                    annotateByClass(m, name);
                }
            }

            // Second pass: infer @class for any remaining nested maps using simple
            // capitalization heuristic and presence of metadata file.
            try {
                for (Map.Entry<String,Object> e : root.entrySet()) {
                    String k = e.getKey(); Object v = e.getValue();
                    if (!(v instanceof Map)) continue;
                    @SuppressWarnings("unchecked") Map<String,Object> child = (Map<String,Object>) v;
                    if (child.containsKey("@class")) continue;
                    if (k == null || k.isBlank()) continue;
                    String cand = Character.toUpperCase(k.charAt(0)) + k.substring(1);
                    try {
                        Method r2 = resolver.getClass().getMethod("resolveForClass", String.class);
                        Object md2 = r2.invoke(resolver, cand);
                        if (md2 != null) {
                            if (child.putIfAbsent("@class", cand) == null) {
                                log.info("MetadataAnnotator: inferred @class='{}' for nested field '{}'", cand, k);
                            }
                            annotateByClass(child, cand);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        } catch (Exception ex) {
            log.debug("MetadataAnnotator failed", ex);
        }
    }

    private void annotateByClass(Map<String,Object> node, String className) {
        if (node == null || className == null) return;
        // Best-effort: ensure a minimal @class marker exists using the provided
        // className (capitalize common field names like 'customer' -> 'Customer')
        try {
            String inferred = className;
            if (inferred != null && !inferred.isBlank()) {
                if (!Character.isUpperCase(inferred.charAt(0))) {
                    inferred = Character.toUpperCase(inferred.charAt(0)) + inferred.substring(1);
                }
                node.putIfAbsent("@class", inferred);
            }
            Method resolve = null;
            try { resolve = resolver.getClass().getMethod("resolveForClass", String.class); } catch (NoSuchMethodException e) { return; }
            Object md = resolve.invoke(resolver, className);
            if (md == null) {
                log.debug("MetadataAnnotator: no metadata found for class '{}'", className);
                return;
            }
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
                if (childClass == null) { try { Field fc = fd.getClass().getField("class"); Object cv = fc.get(fd); if (cv != null) childClass = String.valueOf(cv); } catch (Exception ignored) {} }
                if (childClass == null) { try { Field fc = fd.getClass().getField("type"); Object cv = fc.get(fd); if (cv != null) childClass = String.valueOf(cv); } catch (Exception ignored) {} }

                try { Field fe = fd.getClass().getField("elementClass"); Object ev = fe.get(fd); if (ev != null) elementClass = String.valueOf(ev); } catch (Exception ignored) {}
                if (elementClass == null) { try { Field fe = fd.getClass().getField("elementType"); Object ev = fe.get(fd); if (ev != null) elementClass = String.valueOf(ev); } catch (Exception ignored) {} }
                if (elementClass == null) {
                    try {
                        Field ft = fd.getClass().getField("type"); Object tv = ft.get(fd);
                        if (tv != null) {
                            boolean isArray = false;
                            try { Field fa = fd.getClass().getField("isArray"); Object av = fa.get(fd); if (av instanceof Boolean) isArray = (Boolean) av; } catch (Exception ignored) {}
                            try { Field fa = fd.getClass().getField("isList"); Object av = fa.get(fd); if (av instanceof Boolean) isArray = isArray || (Boolean) av; } catch (Exception ignored) {}
                            if (isArray) elementClass = String.valueOf(tv);
                        }
                    } catch (Exception ignored) {}
                }

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
                } else if (elementClass != null && val instanceof Map) {
                    // support hashmap-like fields: map<string,object>
                    @SuppressWarnings("unchecked") Map<String,Object> map = (Map<String,Object>) val;
                    for (Map.Entry<String,Object> e : map.entrySet()) {
                        Object it = e.getValue();
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
