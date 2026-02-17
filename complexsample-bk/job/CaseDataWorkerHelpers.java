package vn.com.fecredit.complexsample.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import vn.com.fecredit.complexsample.entity.CasePlainOrder;

import java.util.Map;

public final class CaseDataWorkerHelpers {
    private CaseDataWorkerHelpers() {}

    public static Map<String, Object> parsePayload(ObjectMapper om, String payload, String caseInstanceId) {
        if (payload == null) return java.util.Collections.emptyMap();
        try { return om.readValue(payload, Map.class); } catch (Exception e) { return java.util.Collections.emptyMap(); }
    }

    public static java.util.Map<String, Object> extractDirectFallbacks(String annotatedJson) {
        Double _dt = null; String _pr = null;
        try { Object o = JsonPath.read(annotatedJson, "$.total"); if (o instanceof Number) _dt = ((Number)o).doubleValue(); } catch (Exception ignored) {}
        try { Object pval = JsonPath.read(annotatedJson, "$.meta.priority"); if (pval != null) _pr = String.valueOf(pval); } catch (Exception ignored) {}
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("total", _dt);
        m.put("priority", _pr);
        return m;
    }

    public static void setCreatedAtIfMissing(CasePlainOrder p, Object rowCreatedAt) {
        if (rowCreatedAt == null || p.getCreatedAt() != null) return;
        try {
            java.time.OffsetDateTime created = null;
            if (rowCreatedAt instanceof java.time.OffsetDateTime odt) created = odt;
            else if (rowCreatedAt instanceof java.time.Instant inst) created = java.time.OffsetDateTime.ofInstant(inst, java.time.ZoneOffset.UTC);
            else if (rowCreatedAt instanceof java.sql.Timestamp ts) created = java.time.OffsetDateTime.ofInstant(ts.toInstant(), java.time.ZoneOffset.UTC);
            else if (rowCreatedAt instanceof Long l) created = java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(l), java.time.ZoneOffset.UTC);
            if (created != null) p.setCreatedAt(created);
        } catch (Exception ex) { /* ignore */ }
    }

    public static void setRequestedByFromJson(CasePlainOrder p, String annotatedJson) {
        try {
            String req = null;
            try { Object v = JsonPath.read(annotatedJson, "$.startUserId"); if (v != null) req = String.valueOf(v); } catch (Exception ignored) {}
            if (req == null) { try { Object v = JsonPath.read(annotatedJson, "$.requestedBy"); if (v != null) req = String.valueOf(v); } catch (Exception ignored) {} }
            if (req != null) p.setRequestedBy(req);
        } catch (Exception ex) { /* ignore */ }
    }

    public static void ensureDefaultPriority(CasePlainOrder p) { try { if (p.getOrderPriority() == null) p.setOrderPriority("HIGH"); } catch (Exception ex) { /* ignore */ } }
}
