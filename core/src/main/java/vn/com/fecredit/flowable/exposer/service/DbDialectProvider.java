package vn.com.fecredit.flowable.exposer.service;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Provides database dialect detection and SQL generation for portable database operations.
 * Detects the active database via JDBC connection metadata and generates dialect-specific SQL.
 */
@Component
public class DbDialectProvider {

    private static final Logger log = LoggerFactory.getLogger(DbDialectProvider.class);

    /**
     * Detects the database product name from the EntityManager's connection.
     *
     * @param em the EntityManager to extract database info from
     * @return the database product name (e.g., "H2", "MySQL", "PostgreSQL"), or "unknown" if detection fails
     */
    public String detectDatabaseName(EntityManager em) {
        try {
            // Get JDBC connection from EntityManager and extract database product name
            return em.unwrap(java.sql.Connection.class).getMetaData().getDatabaseProductName();
        } catch (Exception ex) {
            log.warn("Failed to detect database name: {}", ex.getMessage());
            return "unknown";
        }
    }

    /**
     * Generates a database-specific UPSERT SQL statement.
     * Supports H2, MySQL/MariaDB, and PostgreSQL with appropriate syntax for each.
     *
     * <p>Upsert Semantics: Updates all non-key columns on duplicate key values (determined by UNIQUE constraint on case_instance_id).
     *
     * @param em the EntityManager for dialect detection
     * @param tableName the target table name (must be valid SQL identifier)
     * @param columnNames list of column names in parameter order
     * @return parameterized SQL string with ? placeholders ready for prepared statement
     */
    public String buildUpsertSql(EntityManager em, String tableName, java.util.List<String> columnNames) {
        if (columnNames == null || columnNames.isEmpty()) {
            throw new IllegalArgumentException("No columns to upsert");
        }

        String dbName = detectDatabaseName(em);
        log.debug("buildUpsertSql: detected database={}", dbName);

        java.util.List<String> placeholders = new java.util.ArrayList<>();
        for (int i = 0; i < columnNames.size(); i++) {
            placeholders.add("?");
        }
        String columns = String.join(", ", columnNames);
        String values = String.join(", ", placeholders);

        // Build UPDATE clause (exclude case_instance_id since it's the conflict key)
        StringBuilder updateClause = new StringBuilder();
        for (String col : columnNames) {
            if (!col.equalsIgnoreCase("case_instance_id")) {
                if (updateClause.length() > 0) updateClause.append(", ");
                updateClause.append(col).append("=VALUES(").append(col).append(")");
            }
        }

        // Require case_instance_id for proper upsert semantics
        if (!columnNames.stream().anyMatch(c -> c.equalsIgnoreCase("case_instance_id"))) {
            log.warn("buildUpsertSql: case_instance_id not in columnNames; falling back to INSERT");
            return String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columns, values);
        }

        String dbLower = dbName.toLowerCase(java.util.Locale.ROOT);

        // H2 Database
        if (dbLower.contains("h2")) {
            return String.format("MERGE INTO %s (%s) KEY(case_instance_id) VALUES (%s)", tableName, columns, values);
        }

        // MySQL/MariaDB
        if (dbLower.contains("mysql") || dbLower.contains("mariadb")) {
            if (updateClause.length() > 0) {
                return String.format("INSERT INTO %s (%s) VALUES (%s) ON DUPLICATE KEY UPDATE %s", tableName, columns, values, updateClause);
            }
            return String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columns, values);
        }

        // PostgreSQL
        if (dbLower.contains("postgres")) {
            if (updateClause.length() > 0) {
                return String.format(
                    "INSERT INTO %s (%s) VALUES (%s) ON CONFLICT(case_instance_id) DO UPDATE SET %s",
                    tableName, columns, values, updateClause
                );
            }
            return String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columns, values);
        }

        // Default: H2 MERGE syntax (widely compatible)
        log.debug("buildUpsertSql: unknown database {}, defaulting to MERGE syntax", dbName);
        return String.format("MERGE INTO %s (%s) KEY(case_instance_id) VALUES (%s)", tableName, columns, values);
    }
}
