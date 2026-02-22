package vn.com.fecredit.flowable.exposer.job;

import java.util.Map;

/**
 * Simple column-related utilities that are used by both schema and row helpers.
 * Keeping them in their own class keeps other helpers focused and small.
 */
public class CaseDataWorkerColumnHelper {
    private final CaseDataWorkerDialectHelper dialect;

    public CaseDataWorkerColumnHelper(CaseDataWorkerDialectHelper dialect) {
        this.dialect = dialect;
    }

    public String determineColumnType(Object value, String hint) {
        if (hint != null && !hint.isBlank()) {
            String up = hint.toUpperCase(java.util.Locale.ROOT).trim();
            if (up.equals("DECIMAL")) {
                return "DECIMAL(19,4)";
            }
            if (up.equals("TEXT")) {
                return dialect.isH2() ? "CLOB" : "LONGTEXT";
            }
            if (hint.contains("(")) {
                return hint;
            }
            return up;
        }
        if (value == null) return "VARCHAR(255)";
        if (value instanceof Boolean) return "BOOLEAN";
        if (value instanceof Integer || value instanceof Long) return "BIGINT";
        if (value instanceof Float || value instanceof Double) return "DECIMAL(19,4)";
        if (value instanceof java.time.temporal.Temporal || value instanceof java.util.Date) return "TIMESTAMP";
        String s = value.toString();
        if (s.length() > 1024) return dialect.isH2() ? "CLOB" : "LONGTEXT";
        return "VARCHAR(255)";
    }

    public boolean isValidIdentifier(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) return false;
        return identifier.matches("^[a-zA-Z_$][a-zA-Z0-9_$]*$");
    }

    public boolean isEmptyResult(Object val) {
        if (val == null) return true;
        if (val instanceof java.util.Collection && ((java.util.Collection<?>) val).isEmpty()) return true;
        if (val instanceof java.util.Map && ((java.util.Map<?, ?>) val).isEmpty()) return true;
        String s = val.toString().trim();
        return s.equals("[]") || s.equals("{}");
    }

    public String safeQuote(String id) {
        if (id == null) return "";
        if (isValidIdentifier(id)) return id;
        return "\"" + id + "\"";
    }

    public java.util.List<String> upsertColumnOrder(Map<String, Object> row) {
        java.util.List<String> cols = new java.util.ArrayList<>();
        if (row.containsKey("case_instance_id")) cols.add("case_instance_id");
        for (String k : row.keySet()) {
            if (k.equalsIgnoreCase("case_instance_id")) continue;
            cols.add(k);
        }
        return cols;
    }
}
