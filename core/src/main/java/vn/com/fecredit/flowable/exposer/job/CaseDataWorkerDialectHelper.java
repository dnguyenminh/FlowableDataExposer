package vn.com.fecredit.flowable.exposer.job;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * Database-dialect related helpers extracted from the worker service.  Keeping
 * them in a separate class keeps the schema helper under the 200‑line limit.
 */
public class CaseDataWorkerDialectHelper {
    private final JdbcTemplate jdbc;
    private Boolean cachedIsH2 = null;

    public CaseDataWorkerDialectHelper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isH2() {
        if (cachedIsH2 != null) return cachedIsH2;
        try {
            java.sql.Connection c = jdbc.getDataSource() == null ? null : jdbc.getDataSource().getConnection();
            if (c != null) {
                String db = c.getMetaData().getDatabaseProductName();
                if (db != null && db.toLowerCase(java.util.Locale.ROOT).contains("h2")) {
                    cachedIsH2 = true;
                } else {
                    cachedIsH2 = false;
                }
                c.close();
            }
        } catch (Exception ignored) {
            cachedIsH2 = false;
        }
        return cachedIsH2;
    }

    public void h2SelectUpdateInsert(String actualTable, java.util.List<String> columnOrder, Map<String, Object> rowValues) {
        if (actualTable == null || actualTable.trim().isEmpty() || columnOrder == null || columnOrder.isEmpty()) return;
        try {
            Object keyVal = rowValues.get("case_instance_id");
            String placeholders = String.join(", ", java.util.Collections.nCopies(columnOrder.size(), "?"));
            Object[] insertParams = columnOrder.stream().map(rowValues::get).toArray();
            String safeCols = String.join(", ", columnOrder.stream().map(this::safeQuote).toArray(String[]::new));

            if (keyVal == null) {
                jdbc.update(String.format("INSERT INTO %s (%s) VALUES (%s)", safeQuote(actualTable), safeCols, placeholders), insertParams);
                return;
            }

            Integer count = null;
            try {
                String existsSql = String.format("SELECT COUNT(1) FROM %s WHERE %s = ?", safeQuote(actualTable), safeQuote("case_instance_id"));
                count = jdbc.queryForObject(existsSql, new Object[]{keyVal}, Integer.class);
            } catch (Exception ex) {
                // existence check failure; ignore
            }

            if (count != null && count > 0) {
                StringBuilder set = new StringBuilder();
                java.util.List<Object> params = new java.util.ArrayList<>();
                for (String col : columnOrder) {
                    if (col.equalsIgnoreCase("case_instance_id")) continue;
                    if (set.length() > 0) set.append(", ");
                    set.append(safeQuote(col)).append(" = ?");
                    params.add(rowValues.get(col));
                }
                if (params.isEmpty()) return;
                params.add(keyVal);
                jdbc.update(String.format("UPDATE %s SET %s WHERE %s = ?", safeQuote(actualTable), set.toString(), safeQuote("case_instance_id")), params.toArray());
            } else {
                jdbc.update(String.format("INSERT INTO %s (%s) VALUES (%s)", safeQuote(actualTable), safeCols, placeholders), insertParams);
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public String buildUpsertSql(String actualTableName, java.util.List<String> columnOrder) {
        if (columnOrder == null || columnOrder.isEmpty()) throw new IllegalArgumentException("No columns to upsert");
        java.util.List<String> placeholders = new java.util.ArrayList<>();
        for (int i = 0; i < columnOrder.size(); i++) placeholders.add("?");
        String values = String.join(", ", placeholders);

        String keyCol = null;
        StringBuilder updateAssignments = new StringBuilder();
        for (String col : columnOrder) {
            if (col.equalsIgnoreCase("case_instance_id")) {
                keyCol = col;
                continue;
            }
            if (updateAssignments.length() > 0) updateAssignments.append(", ");
            updateAssignments.append(safeQuote(col)).append("=src.").append(safeQuote(col));
        }

        if (keyCol == null) {
            String colsQuoted = String.join(", ", columnOrder.stream().map(this::safeQuote).toArray(String[]::new));
            return String.format("INSERT INTO %s (%s) VALUES (%s)", safeQuote(actualTableName), colsQuoted, values);
        }

        try {
            String db = "";
            try { db = jdbc.getDataSource() == null ? "" : jdbc.getDataSource().getConnection().getMetaData().getDatabaseProductName(); } catch (Exception ignored) {}
            String dbLower = db == null ? "" : db.toLowerCase(java.util.Locale.ROOT);

            if (dbLower.contains("h2")) {
                return "__H2_SELECT_UPDATE_INSERT__";
            } else if (dbLower.contains("postgres") || dbLower.contains("oracle") || dbLower.contains("sqlserver")) {
                String[] srcColsArr = columnOrder.stream().map(this::safeQuote).toArray(String[]::new);
                String srcCols = String.join(", ", srcColsArr);
                String insertVals = java.util.stream.IntStream.range(0, columnOrder.size()).mapToObj(i -> "src." + safeQuote(columnOrder.get(i))).collect(java.util.stream.Collectors.joining(", "));
                StringBuilder merge = new StringBuilder();
                merge.append("MERGE INTO ").append(safeQuote(actualTableName)).append(" AS t USING (VALUES (").append(values).append(")) AS src(").append(srcCols).append(") ON t.").append(safeQuote(keyCol)).append(" = src.").append(safeQuote(keyCol)).append(" ");
                if (updateAssignments.length() > 0) merge.append("WHEN MATCHED THEN UPDATE SET ").append(updateAssignments.toString()).append(" ");
                merge.append("WHEN NOT MATCHED THEN INSERT (").append(srcCols).append(") VALUES (").append(insertVals).append(")");
                return merge.toString();
            } else if (dbLower.contains("mysql") || dbLower.contains("mariadb")) {
                String colsQuoted = String.join(", ", columnOrder.stream().map(this::safeQuote).toArray(String[]::new));
                StringBuilder updateClauseMysql = new StringBuilder();
                for (String col : columnOrder) {
                    if (col.equalsIgnoreCase("case_instance_id")) continue;
                    if (updateClauseMysql.length() > 0) updateClauseMysql.append(", ");
                    updateClauseMysql.append(safeQuote(col)).append("=VALUES(").append(safeQuote(col)).append(")");
                }
                return String.format("INSERT INTO %s (%s) VALUES (%s) ON DUPLICATE KEY UPDATE %s", safeQuote(actualTableName), colsQuoted, values, updateClauseMysql.toString());
            }
            String colsQuoted = String.join(", ", columnOrder.stream().map(this::safeQuote).toArray(String[]::new));
            return String.format("INSERT INTO %s (%s) VALUES (%s)", safeQuote(actualTableName), colsQuoted, values);
        } catch (Exception ignored) {
            String colsQuoted = String.join(", ", columnOrder.stream().map(this::safeQuote).toArray(String[]::new));
            return String.format("INSERT INTO %s (%s) VALUES (%s)", safeQuote(actualTableName), colsQuoted, values);
        }
    }

    private String safeQuote(String id) {
        if (id == null) return "";
        if (id.matches("^[a-zA-Z_$][a-zA-Z0-9_$]*$")) return id;
        return "\"" + id + "\"";
    }
}
