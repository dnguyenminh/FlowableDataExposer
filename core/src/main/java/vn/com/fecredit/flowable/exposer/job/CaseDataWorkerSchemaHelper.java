package vn.com.fecredit.flowable.exposer.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import vn.com.fecredit.flowable.exposer.job.CaseDataWorkerDialectHelper;
import vn.com.fecredit.flowable.exposer.job.CaseDataWorkerColumnHelper;

/**
 * Schema maintenance utilities used by the worker service.  Kept small and
 * focused so each class stays under 200 lines.
 */
public class CaseDataWorkerSchemaHelper {
    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    // simple cache fields mirrored from previous implementation (moved to TableHelper)
    private Boolean cachedIsH2 = null;
    private final CaseDataWorkerTableHelper tables;

    // collaborators extracted to keep class size small
    private final CaseDataWorkerDialectHelper dialect;
    private final CaseDataWorkerColumnHelper column;

    public CaseDataWorkerSchemaHelper(JdbcTemplate jdbc, ObjectMapper om, CaseDataWorkerDialectHelper dialect) {
        this.jdbc = jdbc;
        this.om = om;
        this.dialect = dialect;
        this.column = new CaseDataWorkerColumnHelper(dialect);
        this.tables = new CaseDataWorkerTableHelper(jdbc);
    }

    public boolean tableExists(String tableName) {
        return tables.tableExists(tableName);
    }

    public void ensureColumnsPresent(String actualTableName, Map<String, Object> rowValues, Map<String, String> columnTypeHints) {
        try {
            Set<String> existing = getExistingColumns(actualTableName);
            for (String col : rowValues.keySet()) {
                if (col.equalsIgnoreCase("case_instance_id") || col.equalsIgnoreCase("id")) continue;
                if (existing.contains(col.toUpperCase())) continue;
                if (!column.isValidIdentifier(col)) continue;
                String hint = columnTypeHints == null ? null : columnTypeHints.get(col);
                String colType = column.determineColumnType(rowValues.get(col), hint);
                String alter = String.format("ALTER TABLE %s ADD COLUMN %s %s", column.safeQuote(actualTableName), column.safeQuote(col), colType);
                try {
                    executeDdlAutocommit(alter);
                    existing.add(col.toUpperCase());
                } catch (Exception ex) {
                    // ignore
                }
            }
        } catch (Exception ex) {
            // ignore
        }
    }

    public Set<String> getExistingColumns(String actualTableName) {
        return tables.getExistingColumns(actualTableName);
    }

    public void executeDdlAutocommit(String sql) {
        if (sql == null || sql.isBlank()) return;
        java.sql.Connection conn = null;
        java.sql.Statement st = null;
        try {
            conn = jdbc.getDataSource() == null ? null : jdbc.getDataSource().getConnection();
            if (conn == null) return;
            boolean prevAuto = true;
            try {
                try { prevAuto = conn.getAutoCommit(); } catch (Exception ignored) {}
                conn.setAutoCommit(true);
                st = conn.createStatement();
                st.execute(sql);
            } finally {
                if (st != null) try { st.close(); } catch (Exception ignored) {}
                try { conn.setAutoCommit(prevAuto); } catch (Exception ignored) {}
            }
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (msg.contains("already exists") || msg.contains("column already exists") || msg.contains("duplicate table")) {
                return;
            }
            throw new RuntimeException(ex);
        } finally {
            if (conn != null) try { conn.close(); } catch (Exception ignored) {}
        }
    }

    public void createDefaultWorkTable(String tableName, Map<String, Object> rowValues) {
        if (!column.isValidIdentifier(tableName)) return;
        synchronized (tableName.intern()) {
            if (tableExists(tableName)) return;
            String idColumnDef = dialect.isH2() ? "id VARCHAR(255) DEFAULT RANDOM_UUID() PRIMARY KEY" : "id VARCHAR(255) PRIMARY KEY";
            StringBuilder createTableSql = new StringBuilder();
            createTableSql.append("CREATE TABLE ").append(column.safeQuote(tableName)).append(" (");
            createTableSql.append(idColumnDef).append(", ");
            createTableSql.append("case_instance_id VARCHAR(255) NOT NULL UNIQUE, ");
            String payloadType = dialect.isH2() ? "CLOB" : "LONGTEXT";
            createTableSql.append("plain_payload ").append(payloadType).append(", ");
            createTableSql.append("requested_by VARCHAR(255)");
            createTableSql.append(", created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
            createTableSql.append(", updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
            createTableSql.append(")");
            try { executeDdlAutocommit(createTableSql.toString()); } catch (Exception ignored) {}
            String actualTable = resolveActualTableName(tableName);
            ensureColumnsPresent(actualTable, rowValues, null);
            try {
                String idx1 = String.format("CREATE INDEX IF NOT EXISTS idx_%s_case_instance_id ON %s(case_instance_id)", tableName, column.safeQuote(actualTable));
                String idx2 = String.format("CREATE INDEX IF NOT EXISTS idx_%s_created_at ON %s(created_at)", tableName, column.safeQuote(actualTable));
                executeDdlAutocommit(idx1);
                executeDdlAutocommit(idx2);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Lookup the canonical (possibly upper‑cased) table name recorded when the
     * table was discovered previously.  Falls back to the logical name when
     * no mapping exists.
     */
    public String resolveActualTableName(String logicalName) {
        return tables.resolveActualTableName(logicalName);
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

