package vn.com.fecredit.flowable.exposer.job;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight helper that knows how to probe the database for the existence of
 * tables and their columns, caching answers to avoid hammering metadata APIs.
 * Splitting this out from {@link CaseDataWorkerSchemaHelper} keeps that class
 * under the desired 200‑line threshold.
 */
public class CaseDataWorkerTableHelper {
    private final JdbcTemplate jdbc;

    private volatile Set<String> cachedExistingTables = null;
    final Map<String, String> logicalToActualTableNames = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Set<String>> cachedTableColumns = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.Semaphore dbThrottle = new java.util.concurrent.Semaphore(12);

    public CaseDataWorkerTableHelper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tableExists(String tableName) {
        if (tableName == null) return false;
        String up = tableName.toUpperCase(java.util.Locale.ROOT);
        Set<String> localCached = cachedExistingTables;
        if (localCached != null && localCached.contains(up)) {
            return true;
        }
        try {
            java.sql.Connection conn = null;
            boolean permitAcquired = false;
            try {
                try { permitAcquired = dbThrottle.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                try {
                    conn = jdbc.getDataSource() == null ? null : jdbc.getDataSource().getConnection();
                    if (conn != null) {
                        try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, null, up, new String[]{"TABLE"})) {
                            if (rs.next()) {
                                String found = rs.getString("TABLE_NAME");
                                logicalToActualTableNames.put(up, found);
                                if (cachedExistingTables == null) cachedExistingTables = new HashSet<>();
                                cachedExistingTables.add(up);
                                return true;
                            }
                        }
                        try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, null, null, new String[]{"TABLE"})) {
                            while (rs.next()) {
                                String foundName = rs.getString("TABLE_NAME");
                                if (up.equalsIgnoreCase(foundName)) {
                                    logicalToActualTableNames.put(up, foundName);
                                    if (cachedExistingTables == null) cachedExistingTables = new HashSet<>();
                                    cachedExistingTables.add(up);
                                    return true;
                                }
                            }
                        }
                    }
                } finally {
                    if (conn != null) try { conn.close(); } catch (Exception ignored) {}
                }
            } finally {
                if (permitAcquired) dbThrottle.release();
            }
        } catch (Exception ex) {
            // ignore
        }
        try {
            jdbc.queryForObject("SELECT 1 FROM " + safeQuote(tableName) + " LIMIT 1", Integer.class);
            if (cachedExistingTables == null) cachedExistingTables = new HashSet<>();
            cachedExistingTables.add(up);
            return true;
        } catch (Exception ex2) {
            return false;
        }
    }

    public Set<String> getExistingColumns(String actualTableName) {
        if (actualTableName == null) return java.util.Collections.emptySet();
        String upLogical = actualTableName.toUpperCase(java.util.Locale.ROOT);
        Set<String> cached = cachedTableColumns.get(upLogical);
        if (cached != null) return cached;

        Set<String> cols = new HashSet<>();
        java.sql.Connection conn = null;
        try {
            conn = jdbc.getDataSource() == null ? null : jdbc.getDataSource().getConnection();
            if (conn != null) {
                java.sql.ResultSet rs = null;
                try {
                    rs = conn.getMetaData().getColumns(null, null, actualTableName, null);
                    while (rs.next()) {
                        String col = rs.getString("COLUMN_NAME");
                        if (col != null) cols.add(col.toUpperCase(java.util.Locale.ROOT));
                    }
                    if (cols.isEmpty()) {
                        rs.close();
                        rs = conn.getMetaData().getColumns(null, null, actualTableName.toUpperCase(java.util.Locale.ROOT), null);
                        while (rs.next()) {
                            String col = rs.getString("COLUMN_NAME");
                            if (col != null) cols.add(col.toUpperCase(java.util.Locale.ROOT));
                        }
                    }
                    if (!cols.isEmpty()) {
                        cachedTableColumns.put(upLogical, cols);
                        return cols;
                    }
                } finally {
                    if (rs != null) try { rs.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ex) {
            // ignore
        } finally {
            if (conn != null) try { conn.close(); } catch (Exception ignored) {}
        }
        return cols;
    }

    public String resolveActualTableName(String logicalName) {
        if (logicalName == null) return null;
        String up = logicalName.toUpperCase(java.util.Locale.ROOT);
        String actual = logicalToActualTableNames.get(up);
        return actual != null ? actual : logicalName;
    }

    private String safeQuote(String id) {
        if (id == null) return "";
        if (id.matches("^[a-zA-Z_$][a-zA-Z0-9_$]*$")) return id;
        return "\"" + id + "\"";
    }
}