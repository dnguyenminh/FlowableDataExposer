package vn.com.fecredit.flowable.exposer.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Database access wrapper using JPA EntityManager with native queries.
 * Provides database-agnostic operations for dynamic table access without embedding SQL dialect logic.
 * Replaces direct JDBC/JdbcTemplate usage with JPA abstraction for better database portability.
 */
@Component
public class DbAccessService {

    private static final Logger log = LoggerFactory.getLogger(DbAccessService.class);

    /**
     * Executes a native SQL update/insert/delete statement with parameterized values.
     * Uses JPA EntityManager instead of JdbcTemplate for better portability.
     *
     * @param em the EntityManager to execute the query
     * @param sql the parameterized SQL statement (use ? for placeholders)
     * @param params the parameter values in order
     * @return the number of rows affected
     */
    public int executeUpdate(EntityManager em, String sql, Object... params) {
        try {
            Query query = em.createNativeQuery(sql);
            for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
            }
            return query.executeUpdate();
        } catch (Exception ex) {
            log.error("executeUpdate failed: {}", ex.getMessage(), ex);
            throw new RuntimeException("Database update failed", ex);
        }
    }

    /**
     * Executes a native SQL query and returns results as Object arrays.
     * Suitable for SELECT queries with dynamic column counts.
     *
     * @param em the EntityManager to execute the query
     * @param sql the parameterized SQL statement (use ? for placeholders)
     * @param params the parameter values in order
     * @return list of Object arrays representing rows
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> queryForList(EntityManager em, String sql, Object... params) {
        try {
            Query query = em.createNativeQuery(sql);
            for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
            }
            return query.getResultList();
        } catch (Exception ex) {
            log.warn("queryForList failed: {}", ex.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Executes a native SQL query and returns the first result as an Object array.
     *
     * @param em the EntityManager to execute the query
     * @param sql the parameterized SQL statement (use ? for placeholders)
     * @param params the parameter values in order
     * @return the first row as Object array, or null if no results
     */
    @SuppressWarnings("unchecked")
    public Object[] queryForObject(EntityManager em, String sql, Object... params) {
        try {
            Query query = em.createNativeQuery(sql);
            for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
            }
            List<Object[]> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception ex) {
            log.warn("queryForObject failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Executes a native SQL statement (DDL or DML).
     * Used for CREATE TABLE, ALTER TABLE, CREATE INDEX, etc.
     *
     * @param em the EntityManager to execute the statement
     * @param sql the SQL statement (no parameters)
     */
    public void execute(EntityManager em, String sql) {
        try {
            Query query = em.createNativeQuery(sql);
            query.executeUpdate();
            log.debug("execute: completed SQL: {}", sql);
        } catch (Exception ex) {
            log.warn("execute failed for SQL [{}]: {}", sql, ex.getMessage());
            // Don't rethrow — allow DDL operations to fail gracefully (e.g., table already exists)
        }
    }

    /**
     * Checks if a table exists by querying information schema.
     *
     * @param em the EntityManager to use
     * @param tableName the table name to check
     * @return true if table exists, false otherwise
     */
    public boolean tableExists(EntityManager em, String tableName) {
        try {
            List<Object[]> results = queryForList(em,
                    "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?",
                    tableName.toUpperCase());
            return !results.isEmpty();
        } catch (Exception ex) {
            log.debug("tableExists failed for {}: {}", tableName, ex.getMessage());
            // Fallback: try a simple query
            try {
                queryForObject(em, "SELECT 1 FROM " + tableName + " LIMIT 1");
                return true;
            } catch (Exception ex2) {
                return false;
            }
        }
    }

    /**
     * Gets existing column names for a table.
     *
     * @param em the EntityManager to use
     * @param tableName the table name
     * @return set of column names in uppercase
     */
    public java.util.Set<String> getExistingColumns(EntityManager em, String tableName) {
        try {
            List<Object[]> rows = queryForList(em,
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ?",
                    tableName.toUpperCase());
            java.util.Set<String> cols = new java.util.HashSet<>();
            for (Object[] row : rows) {
                if (row[0] != null) {
                    cols.add(row[0].toString().toUpperCase());
                }
            }
            return cols;
        } catch (Exception ex) {
            log.debug("getExistingColumns failed for {}: {}", tableName, ex.getMessage());
            return new java.util.HashSet<>();
        }
    }
}
