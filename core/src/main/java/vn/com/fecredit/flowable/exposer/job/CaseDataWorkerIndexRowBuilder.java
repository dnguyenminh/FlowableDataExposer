package vn.com.fecredit.flowable.exposer.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import java.util.Map;

/**
 * Helper responsible for converting extracted JSON fragments into index rows.
 * Pulled out of {@link CaseDataWorkerIndexHelper} to keep that class under the
 * 200‑line limit.
 */
public class CaseDataWorkerIndexRowBuilder {
    private final ObjectMapper om;

    public CaseDataWorkerIndexRowBuilder(ObjectMapper om) {
        this.om = om;
    }

    Object tryAlternateRoots(String annotatedJson, String rootPath) {
        if (rootPath == null || annotatedJson == null) return null;
        try {
            java.util.List<String> alts = new java.util.ArrayList<>();
            if (rootPath.startsWith("$.")) {
                String body = rootPath.substring(2);
                alts.add("$['" + body + "']");
                alts.add("$." + body);
                if (body.endsWith("s") && body.length()>1) alts.add("$." + body.substring(0, body.length()-1));
                else alts.add("$." + body + "s");
            } else if (rootPath.startsWith("$['") && rootPath.endsWith("']")) {
                String key = rootPath.substring(3, rootPath.length()-2);
                alts.add("$." + key);
                if (key.endsWith("s") && key.length()>1) alts.add("$." + key.substring(0, key.length()-1));
                else alts.add("$." + key + "s");
            }
            if (!rootPath.startsWith("$['") && rootPath.startsWith("$.")) {
                String body = rootPath.substring(2);
                alts.add("$['" + body + "']");
            }
            for (String a : alts) {
                try { Object r = JsonPath.read(annotatedJson, a); if (r != null) return r; } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    String toJsonSafe(Object obj) {
        try { return om.writeValueAsString(obj); } catch (Exception e) { return obj == null ? "null" : obj.toString(); }
    }

    Map<String, Object> buildIndexRow(vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition def,
                                      String caseInstanceId,
                                      String jsonForItem) {
        return buildIndexRow(def, caseInstanceId, jsonForItem, null);
    }

    Map<String, Object> buildIndexRow(vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition def,
                                      String caseInstanceId,
                                      String jsonForItem,
                                      Object rowCreatedAt) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("case_instance_id", caseInstanceId);
        row.put("plain_payload", jsonForItem);

        if (def == null || def.mappings == null) {
            return row;
        }

        for (vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition.IndexField f : def.mappings) {
            Object val = extractValue(f, jsonForItem, rowCreatedAt);
            if (!isEmpty(val)) {
                row.put(f.plainColumn, val);
            }
        }
        return row;
    }

    private Object extractValue(vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition.IndexField f,
                                String jsonForItem,
                                Object rowCreatedAt) {
        String rawPath = f.jsonPath == null ? "$" : f.jsonPath.trim();
        java.util.List<String> candidates = buildCandidates(rawPath);
        Object val = tryCandidates(jsonForItem, candidates);
        if (val == null) val = tryCandidatesOnParsed(jsonForItem, candidates, rawPath);
        if (val == null) val = applySpecialFallbacks(rawPath, jsonForItem, rowCreatedAt);
        return val;
    }

    private java.util.List<String> buildCandidates(String rawPath) {
        java.util.List<String> c = new java.util.ArrayList<>();
        c.add(rawPath);
        if (rawPath.startsWith("$_")) {
            c.add("$." + rawPath.substring(1));
            c.add("$['" + rawPath.substring(1) + "']");
        }
        if (rawPath.startsWith("$.")) {
            c.add("$._value" + rawPath.substring(1));
            try {
                String[] parts = rawPath.substring(2).split("\\.");
                StringBuilder b = new StringBuilder("$");
                for (String p : parts) b.append("['").append(p).append("']");
                c.add(b.toString());
            } catch (Exception ignored) {}
        }
        try {
            if (rawPath.startsWith("$.") && rawPath.contains(".")) {
                String leaf = rawPath.substring(rawPath.lastIndexOf('.') + 1);
                if (leaf != null && !leaf.isBlank()) c.add("$.." + leaf);
            }
            if (rawPath.toLowerCase().contains("rules")) {
                c.add("$..rules");
                c.add("$..rule");
                c.add("$..discount");
            }
        } catch (Exception ignored) {}
        return c;
    }

    private Object tryCandidates(String json, java.util.List<String> candidates) {
        if (json == null) return null;
        for (String p : candidates) {
            try {
                Object got = JsonPath.read(json, p);
                if (got != null) return got;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Object tryCandidatesOnParsed(String json, java.util.List<String> cand, String rawPath) {
        if (json == null) return null;
        try {
            Object parsed = om.readValue(json, Object.class);
            for (String p : cand) {
                try {
                    Object got = JsonPath.read(parsed, p);
                    if (got != null) return got;
                } catch (Exception ignored) {}
            }
            if (rawPath.startsWith("$.") && parsed instanceof java.util.Map) {
                Object cur = parsed;
                String[] parts = rawPath.substring(2).split("\\.");
                for (String p : parts) {
                    if (cur instanceof java.util.Map) cur = ((java.util.Map<?, ?>) cur).get(p);
                    else { cur = null; break; }
                }
                if (cur != null) return cur;
            }
            if (parsed instanceof java.util.Map && rawPath.endsWith(".name")) {
                try {
                    String[] parts = rawPath.substring(2).split("\\.");
                    Object cur = parsed;
                    for (int i = 0; i < parts.length - 1; i++) {
                        if (cur instanceof java.util.Map) cur = ((java.util.Map<?, ?>) cur).get(parts[i]); else { cur = null; break; }
                    }
                    if (cur instanceof java.util.Map) {
                        java.util.Map<?,?> m = (java.util.Map<?,?>) cur;
                        java.util.List<String> variants = java.util.Arrays.asList("name","fullName","full_name","displayName","display_name","fullname","name","Name");
                        String root = parts.length>1 ? parts[parts.length-2] : null;
                        if (root != null) {
                            variants = new java.util.ArrayList<>(variants);
                            variants.add(root + "Name");
                            variants.add(root + "_name");
                        }
                        for (String k : variants) {
                            for (Object keyObj : m.keySet()) {
                                if (keyObj == null) continue;
                                String key = keyObj.toString();
                                if (key.equalsIgnoreCase(k)) {
                                    Object got = m.get(keyObj);
                                    if (got != null) return got;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (parsed instanceof java.util.Map && rawPath.toLowerCase().contains("rule")) {
                try {
                    java.util.List<Object> found = new java.util.ArrayList<>();
                    java.util.function.BiConsumer<java.util.Map<?,?>,String> scan = new java.util.function.BiConsumer<java.util.Map<?,?>,String>(){
                        public void accept(java.util.Map<?,?> m, String path) {
                            for (Object k : m.keySet()) {
                                Object v = m.get(k);
                                String key = k==null?"":k.toString();
                                if ("discount".equalsIgnoreCase(key) || key.toLowerCase().contains("discount")) found.add(v);
                                if (v instanceof java.util.Map) this.accept((java.util.Map<?,?>)v, path + "/" + key);
                                if (v instanceof java.util.List) for (Object el : (java.util.List<?>)v) if (el instanceof java.util.Map) this.accept((java.util.Map<?,?>)el, path + "/" + key);
                            }
                        }
                    };
                    scan.accept((java.util.Map<?,?>) parsed, "$.");
                    if (!found.isEmpty()) return found.get(0);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Object applySpecialFallbacks(String rawPath, String json, Object rowCreatedAt) {
        Object val = null;
        try {
            if ("$.requestedBy".equalsIgnoreCase(rawPath) || "$.requested_by".equalsIgnoreCase(rawPath)) {
                try { val = JsonPath.read(json, "$.initiator"); } catch (Exception ignored) {}
                if (val == null) {
                    try { val = JsonPath.read(json, "$.requestedBy"); } catch (Exception ignored) {}
                }
            }
            if (val == null && "$.createTime".equalsIgnoreCase(rawPath)) {
                try { val = JsonPath.read(json, "$.createdAt"); } catch (Exception ignored) {}
                if (val == null) try { val = JsonPath.read(json, "$.created_at"); } catch (Exception ignored) {}
                if (val == null) val = rowCreatedAt;
            }
        } catch (Exception ignored) {}
        return val;
    }

    private boolean isEmpty(Object val) {
        if (val == null) return true;
        if (val instanceof java.util.Collection && ((java.util.Collection<?>) val).isEmpty()) return true;
        if (val instanceof java.util.Map && ((java.util.Map<?, ?>) val).isEmpty()) return true;
        String s = val.toString().trim();
        return s.equals("[]") || s.equals("{}");
    }
}
