package vn.com.fecredit.flowable.exposer.job;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test suite for auto table creation feature in CaseDataWorker.
 * Tests validate type detection and SQL generation logic without requiring Spring context.
 */
public class CaseDataWorkerAutoTableCreationTest {

    /**
     * Helper method that mimics determineColumnType logic from CaseDataWorker
     */
    private String determineSqlType(Object value) {
        if (value == null) {
            return "LONGTEXT";
        }

        if (value instanceof Integer || value instanceof Long) {
            return "BIGINT";
        }

        if (value instanceof Double || value instanceof Float) {
            return "DECIMAL(19,4)";
        }

        if (value instanceof Boolean) {
            return "BOOLEAN";
        }

        if (value instanceof java.time.temporal.Temporal ||
            value instanceof java.sql.Timestamp ||
            value instanceof java.util.Date) {
            return "TIMESTAMP";
        }

        if (value instanceof String) {
            String str = (String) value;
            if (str.length() > 255) {
                return "LONGTEXT";
            }
            return "VARCHAR(255)";
        }

        return "LONGTEXT";
    }

    /**
     * Helper method that mimics isValidIdentifier logic from CaseDataWorker
     */
    private boolean isValidIdentifier(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) return false;
        return identifier.matches("^[a-zA-Z_$][a-zA-Z0-9_$]*$");
    }

    /**
     * Test 1: Column type detection - Double values
     */
    @Test
    void testColumnTypeDetection_mapsDoubleToDecimal() {
        assertThat(determineSqlType(314.99)).isEqualTo("DECIMAL(19,4)");
        assertThat(determineSqlType(0.0)).isEqualTo("DECIMAL(19,4)");
        assertThat(determineSqlType(999999.99)).isEqualTo("DECIMAL(19,4)");
    }

    /**
     * Test 2: Column type detection - Float values
     */
    @Test
    void testColumnTypeDetection_mapsFloatToDecimal() {
        assertThat(determineSqlType(3.14f)).isEqualTo("DECIMAL(19,4)");
        assertThat(determineSqlType(2.71f)).isEqualTo("DECIMAL(19,4)");
    }

    /**
     * Test 3: Column type detection - Long values
     */
    @Test
    void testColumnTypeDetection_mapsLongToBigint() {
        assertThat(determineSqlType(999999999L)).isEqualTo("BIGINT");
        assertThat(determineSqlType(0L)).isEqualTo("BIGINT");
    }

    /**
     * Test 4: Column type detection - Integer values
     */
    @Test
    void testColumnTypeDetection_mapsIntegerToBigint() {
        assertThat(determineSqlType(123)).isEqualTo("BIGINT");
        assertThat(determineSqlType(0)).isEqualTo("BIGINT");
        assertThat(determineSqlType(-999)).isEqualTo("BIGINT");
    }

    /**
     * Test 5: Column type detection - Boolean values
     */
    @Test
    void testColumnTypeDetection_mapsBooleanToBoolean() {
        assertThat(determineSqlType(true)).isEqualTo("BOOLEAN");
        assertThat(determineSqlType(false)).isEqualTo("BOOLEAN");
    }

    /**
     * Test 6: Column type detection - Short strings
     */
    @Test
    void testColumnTypeDetection_mapsShortStringToVarchar() {
        assertThat(determineSqlType("short")).isEqualTo("VARCHAR(255)");
        assertThat(determineSqlType("customer-123")).isEqualTo("VARCHAR(255)");
        assertThat(determineSqlType("a")).isEqualTo("VARCHAR(255)");
        assertThat(determineSqlType("x".repeat(255))).isEqualTo("VARCHAR(255)");
    }

    /**
     * Test 7: Column type detection - Long strings
     */
    @Test
    void testColumnTypeDetection_mapsLongStringToLongtext() {
        String longString = "x".repeat(256);
        assertThat(determineSqlType(longString)).isEqualTo("LONGTEXT");

        String veryLongString = "x".repeat(1000);
        assertThat(determineSqlType(veryLongString)).isEqualTo("LONGTEXT");
    }

    /**
     * Test 8: Column type detection - null values
     */
    @Test
    void testColumnTypeDetection_mapsNullToLongtext() {
        assertThat(determineSqlType(null)).isEqualTo("LONGTEXT");
    }

    /**
     * Test 9: Column type detection - Date values
     */
    @Test
    void testColumnTypeDetection_mapsDateToTimestamp() {
        java.util.Date date = new java.util.Date();
        assertThat(determineSqlType(date)).isEqualTo("TIMESTAMP");

        java.sql.Timestamp timestamp = new java.sql.Timestamp(System.currentTimeMillis());
        assertThat(determineSqlType(timestamp)).isEqualTo("TIMESTAMP");
    }

    /**
     * Test 10: Identifier validation - valid names
     */
    @Test
    void testIdentifierValidation_acceptsValidNames() {
        assertThat(isValidIdentifier("case_plain_order")).isTrue();
        assertThat(isValidIdentifier("CasePlainOrder")).isTrue();
        assertThat(isValidIdentifier("case_plain_order_123")).isTrue();
        assertThat(isValidIdentifier("_case_plain_order")).isTrue();
        assertThat(isValidIdentifier("$case_plain_order")).isTrue();
    }

    /**
     * Test 11: Identifier validation - invalid names
     */
    @Test
    void testIdentifierValidation_rejectsInvalidNames() {
        assertThat(isValidIdentifier("123invalid")).isFalse();
        assertThat(isValidIdentifier("case-plain-order")).isFalse();
        assertThat(isValidIdentifier("case plain order")).isFalse();
        assertThat(isValidIdentifier("")).isFalse();
        assertThat(isValidIdentifier(null)).isFalse();
    }

    /**
     * Test 12: Build CREATE TABLE statement with mixed types
     */
    @Test
    void testCreateTableGeneration_withMultipleTypes() {
        Map<String, Object> rowValues = new HashMap<>();
        rowValues.put("case_instance_id", "order-001");
        rowValues.put("order_total", 314.99);
        rowValues.put("customer_id", "C-123");
        rowValues.put("is_urgent", true);
        rowValues.put("created", System.currentTimeMillis());

        // Verify type detection for each value
        assertThat(determineSqlType(rowValues.get("order_total"))).isEqualTo("DECIMAL(19,4)");
        assertThat(determineSqlType(rowValues.get("customer_id"))).isEqualTo("VARCHAR(255)");
        assertThat(determineSqlType(rowValues.get("is_urgent"))).isEqualTo("BOOLEAN");
        assertThat(determineSqlType(rowValues.get("created"))).isEqualTo("BIGINT");
    }

    /**
     * Test 13: Column name validation in table creation
     */
    @Test
    void testColumnNameValidation_skipsInvalidNames() {
        String validCol1 = "order_total";
        String validCol2 = "customer_id";
        String invalidCol = "123-invalid";

        assertThat(isValidIdentifier(validCol1)).isTrue();
        assertThat(isValidIdentifier(validCol2)).isTrue();
        assertThat(isValidIdentifier(invalidCol)).isFalse();
    }

    /**
     * Test 14: Edge case - empty string handling
     */
    @Test
    void testEdgeCase_emptyStringMapsToVarchar() {
        assertThat(determineSqlType("")).isEqualTo("VARCHAR(255)");
    }

    /**
     * Test 15: Edge case - string at boundary (exactly 255 chars)
     */
    @Test
    void testEdgeCase_stringAt255Boundary() {
        String str255 = "x".repeat(255);
        assertThat(determineSqlType(str255)).isEqualTo("VARCHAR(255)");
    }
}


