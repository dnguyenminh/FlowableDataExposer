package vn.com.fecredit.flowable.exposer.service;


import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.Locale;

/**
 * Small helper to generate idempotent DDL for "plain" export columns requested by metadata.
 *
 * - Produces H2/Postgres-compatible "ALTER TABLE ... ADD COLUMN IF NOT EXISTS" where possible.
 * - Maps metadata `type` to a reasonable SQL column type.
 * - Intended for use in dev/local migrations or for generating migration SQL that DBAs review.
 */
public final class MetadataDdlGenerator {
    private MetadataDdlGenerator() {}

    public static String sqlTypeFor(String metadataType) {
        if (metadataType == null) return "VARCHAR(255)";
        switch (metadataType.toLowerCase(Locale.ROOT)) {
            case "string": return "VARCHAR(255)";
            case "decimal": return "NUMERIC(19,4)";
            case "double": return "DOUBLE PRECISION";
            case "integer": case "int": return "INT";
            case "long": return "BIGINT";
            case "timestamp": case "datetime": return "TIMESTAMP";
            case "boolean": return "BOOLEAN";
            default: return "VARCHAR(255)";
        }
    }

    private static boolean isValidIdentifier(String id) {
        if (id == null) return false;
        return id.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    /**
     * Generate an idempotent ALTER TABLE statement to add the requested plain column.
     * Uses `IF NOT EXISTS` (supported by H2/Postgres); callers should adapt for other RDBMS.
     * Validates identifiers to avoid SQL injection via metadata.
     */
    public static String generateAddColumnIfNotExists(String tableName, MetadataDefinition.FieldMapping fm) {
        String col = fm.plainColumn != null ? fm.plainColumn : fm.column;
        if (!isValidIdentifier(tableName) || !isValidIdentifier(col)) {
            throw new IllegalArgumentException("Invalid table or column identifier: " + tableName + "/" + col);
        }
        String sqlType = sqlTypeFor(fm.type);
        String nullable = (fm.nullable == null || fm.nullable) ? "" : " NOT NULL";
        return String.format("ALTER TABLE %s ADD COLUMN IF NOT EXISTS %s %s%s;", tableName, col, sqlType, nullable);
    }

    /**
     * Convenience: generate DDL for all mappings that request plain export.
     */
    public static java.util.List<String> generateAddColumnsForMappings(String tableName, java.util.Collection<MetadataDefinition.FieldMapping> mappings) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var m : mappings) {
            if (Boolean.TRUE.equals(m.exportToPlain)) {
                out.add(generateAddColumnIfNotExists(tableName, m));
            }
        }
        return out;
    }
}
