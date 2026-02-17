package vn.com.fecredit.flowable.exposer.service.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Utility class to validate metadata JSON files against the Work Class Metadata Schema.
 *
 * Validates:
 * - JSON structure against work-class-schema.json (loaded dynamically)
 * - Required fields defined in schema: class, tableName
 * - Parent class inheritance (validates parent recursively)
 * - Supports any array field (mappings, fields, etc.) based on schema definition
 * - Detects circular parent references
 */
public class MetadataValidationUtil {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String SCHEMA_PATH = "metadata/work-class-schema.json";
    private static final String SCHEMA_CLASS_PATH = "metadata/classes";

    /**
     * Validation result containing details about validation success/failure
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;

        public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = errors != null ? errors : new ArrayList<>();
            this.warnings = warnings != null ? warnings : new ArrayList<>();
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getErrors() {
            return errors;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ValidationResult{valid=").append(valid);
            if (!errors.isEmpty()) {
                sb.append(", errors=[");
                sb.append(String.join("; ", errors));
                sb.append("]");
            }
            if (!warnings.isEmpty()) {
                sb.append(", warnings=[");
                sb.append(String.join("; ", warnings));
                sb.append("]");
            }
            sb.append("}");
            return sb.toString();
        }
    }

    /**
     * Validates a metadata JSON file against the Work Class Metadata Schema.
     * Also validates parent class if defined.
     *
     * @param resourcePath path to JSON file on classpath (e.g., "metadata/classes/Order.json")
     * @return ValidationResult with detailed error/warning information
     */
    public static ValidationResult validateMetadataFile(String resourcePath) {
        return validateMetadataFile(resourcePath, new HashSet<>());
    }

    /**
     * Internal method that validates metadata file and recursively validates parent classes.
     * @param visitedClasses set of already-validated classes to prevent infinite loops
     */
    private static ValidationResult validateMetadataFile(String resourcePath, Set<String> visitedClasses) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            // Load the metadata file
            InputStream is = MetadataValidationUtil.class.getClassLoader().getResourceAsStream(resourcePath);
            if (is == null) {
                errors.add("Metadata file not found on classpath: " + resourcePath);
                return new ValidationResult(false, errors, warnings);
            }

            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode metadataNode = mapper.readTree(content);

            // Load the schema
            JsonNode schemaNode = loadSchema();
            if (schemaNode == null) {
                errors.add("Work Class Metadata Schema not found on classpath");
                return new ValidationResult(false, errors, warnings);
            }

            // Validate against schema
            validateAgainstSchema(metadataNode, schemaNode, errors);

            // Validate parent class if exists
            if (metadataNode.has("parent")) {
                String parentClass = metadataNode.get("parent").asText();
                String parentPath = SCHEMA_CLASS_PATH + "/" + parentClass + ".json";
                
                if (visitedClasses.contains(parentClass)) {
                    errors.add("Circular parent reference detected: " + parentClass);
                } else {
                    visitedClasses.add(parentClass);
                    ValidationResult parentResult = validateMetadataFile(parentPath, visitedClasses);
                    if (!parentResult.isValid()) {
                        errors.add("Validation failed for parent class '" + parentClass + "'");
                        errors.addAll(parentResult.getErrors());
                    }
                    warnings.addAll(parentResult.getWarnings());
                }
            }

        } catch (Exception ex) {
            errors.add("Failed to parse metadata file: " + ex.getMessage());
        }

        boolean isValid = errors.isEmpty();
        return new ValidationResult(isValid, errors, warnings);
    }

    /**
     * Loads the Work Class Metadata Schema from classpath.
     */
    private static JsonNode loadSchema() {
        try {
            InputStream is = MetadataValidationUtil.class.getClassLoader().getResourceAsStream(SCHEMA_PATH);
            if (is == null) {
                return null;
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return mapper.readTree(content);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Validates metadata JSON against the Work Class Metadata Schema.
     * Checks:
     * - All required fields are present and non-empty
     * - All properties have correct types
     * - Array items (mappings, fields) have required properties
     */
    private static void validateAgainstSchema(JsonNode metadataNode, JsonNode schemaNode, List<String> errors) {
        // Get required fields from schema
        JsonNode requiredNode = schemaNode.path("required");
        if (requiredNode.isArray()) {
            for (JsonNode required : requiredNode) {
                String fieldName = required.asText();
                if (!metadataNode.has(fieldName)) {
                    errors.add("Missing required field: '" + fieldName + "'");
                } else {
                    JsonNode fieldValue = metadataNode.get(fieldName);
                    if (fieldValue.isTextual() && fieldValue.asText().trim().isEmpty()) {
                        errors.add("Required field '" + fieldName + "' is empty");
                    }
                }
            }
        }

        // Validate properties based on schema properties definition
        JsonNode propertiesNode = schemaNode.path("properties");
        if (propertiesNode.isObject()) {
            metadataNode.fields().forEachRemaining(entry -> {
                String fieldName = entry.getKey();
                JsonNode fieldValue = entry.getValue();
                JsonNode fieldSchema = propertiesNode.path(fieldName);

                if (!fieldSchema.isMissingNode()) {
                    validateField(fieldName, fieldValue, fieldSchema, errors);
                }
            });
        }

        // Validate array items (mappings, fields, etc.)
        validateArrayItems(metadataNode, schemaNode, errors);
    }

    /**
     * Validates a single field against its schema definition.
     */
    private static void validateField(String fieldName, JsonNode fieldValue, JsonNode fieldSchema, List<String> errors) {
        String expectedType = fieldSchema.path("type").asText(null);

        if (expectedType != null) {
            if ("string".equals(expectedType) && !fieldValue.isTextual()) {
                errors.add("Field '" + fieldName + "' must be a string, got: " + fieldValue.getNodeType());
            } else if ("integer".equals(expectedType) && !fieldValue.isInt()) {
                errors.add("Field '" + fieldName + "' must be an integer, got: " + fieldValue.getNodeType());
            } else if ("array".equals(expectedType) && !fieldValue.isArray()) {
                errors.add("Field '" + fieldName + "' must be an array, got: " + fieldValue.getNodeType());
            } else if ("object".equals(expectedType) && !fieldValue.isObject()) {
                errors.add("Field '" + fieldName + "' must be an object, got: " + fieldValue.getNodeType());
            }
        }
    }

    /**
     * Validates array items (mappings, fields, etc.) based on schema definition.
     * Supports any array field defined in the schema, not just "mappings".
     */
    private static void validateArrayItems(JsonNode metadataNode, JsonNode schemaNode, List<String> errors) {
        JsonNode propertiesNode = schemaNode.path("properties");

        metadataNode.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode fieldValue = entry.getValue();

            if (fieldValue.isArray()) {
                JsonNode fieldSchema = propertiesNode.path(fieldName);
                JsonNode itemSchema = fieldSchema.path("items");

                if (!itemSchema.isMissingNode()) {
                    validateArrayField(fieldName, fieldValue, itemSchema, errors);
                }
            }
        });
    }

    /**
     * Validates items in an array field according to its item schema.
     */
    private static void validateArrayField(String arrayFieldName, JsonNode arrayValue, JsonNode itemSchema, List<String> errors) {
        if (!arrayValue.isArray()) {
            return;
        }

        JsonNode requiredFields = itemSchema.path("required");
        int index = 0;

        for (JsonNode item : arrayValue) {
            String prefix = arrayFieldName + "[" + index + "]";

            if (item.isObject()) {
                // Validate required fields in array item
                if (requiredFields.isArray()) {
                    for (JsonNode required : requiredFields) {
                        String fieldName = required.asText();
                        if (!item.has(fieldName)) {
                            errors.add(prefix + ": missing required field '" + fieldName + "'");
                        } else {
                            JsonNode fieldValue = item.get(fieldName);
                            if (fieldValue.isTextual() && fieldValue.asText().trim().isEmpty()) {
                                errors.add(prefix + ": field '" + fieldName + "' is empty");
                            }
                        }
                    }
                }

                // Validate item properties against schema
                JsonNode itemProperties = itemSchema.path("properties");
                if (itemProperties.isObject()) {
                    item.fields().forEachRemaining(itemEntry -> {
                        String itemFieldName = itemEntry.getKey();
                        JsonNode itemFieldValue = itemEntry.getValue();
                        JsonNode itemFieldSchema = itemProperties.path(itemFieldName);

                        if (!itemFieldSchema.isMissingNode()) {
                            String itemPrefix = prefix + "." + itemFieldName;
                            validateField(itemPrefix, itemFieldValue, itemFieldSchema, errors);
                        }
                    });
                }
            }

            index++;
        }
    }

    /**
     * Validates that a metadata class follows the schema and handles inheritance.
     * Recursively validates parent class if defined.
     */
    public static ValidationResult validateConsistency(String childResourcePath, String parentResourcePath) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            ValidationResult childResult = validateMetadataFile(childResourcePath);
            ValidationResult parentResult = validateMetadataFile(parentResourcePath);

            errors.addAll(childResult.getErrors());
            errors.addAll(parentResult.getErrors());
            warnings.addAll(childResult.getWarnings());
            warnings.addAll(parentResult.getWarnings());

        } catch (Exception ex) {
            errors.add("Failed to validate consistency: " + ex.getMessage());
        }

        boolean isValid = errors.isEmpty();
        return new ValidationResult(isValid, errors, warnings);
    }

    /**
     * Validates metadata JSON against the Work Class Metadata Schema.
     * This is the main validation method.
     */
    public static ValidationResult validate(String resourcePath) {
        return validateMetadataFile(resourcePath);
    }
}

