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
    private static final String EXPOSE_MAPPING_SCHEMA = "metadata/expose-mapping-schema.json";
    private static final String INDEX_MAPPING_SCHEMA = "metadata/index-mapping-schema.json";

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

            // If the metadata explicitly declares a $schema, use it. Otherwise try a set of known metadata schemas in order.
            if (metadataNode.has("$schema") && metadataNode.get("$schema").isTextual()) {
                String schemaPointer = metadataNode.get("$schema").asText();
                JsonNode schemaNode = loadSchema(schemaPointer);
                if (schemaNode == null) {
                    errors.add("Metadata schema not found on classpath: " + schemaPointer);
                    return new ValidationResult(false, errors, warnings);
                }
                validateAgainstSchema(metadataNode, schemaNode, errors);
            } else {
                // Try a sequence of schemas: class-schema, work-class-schema, expose-mapping-schema, index-mapping-schema
                JsonNode classSchema = loadSchema("metadata/class-schema.json");
                List<String> classErrors = new ArrayList<>();
                if (classSchema != null) {
                    validateAgainstSchema(metadataNode, classSchema, classErrors);
                }

                if (classErrors.isEmpty()) {
                    warnings.add("Validated against class-schema.json");
                } else {
                    JsonNode workSchema = loadSchema(SCHEMA_PATH);
                    List<String> workErrors = new ArrayList<>();
                    if (workSchema != null) {
                        validateAgainstSchema(metadataNode, workSchema, workErrors);
                    }

                    if (workErrors.isEmpty()) {
                        warnings.add("Validated against work-class-schema.json");
                    } else {
                        // Try expose mapping schema
                        JsonNode exposeSchema = loadSchema(EXPOSE_MAPPING_SCHEMA);
                        List<String> exposeErrors = new ArrayList<>();
                        if (exposeSchema != null) {
                            validateAgainstSchema(metadataNode, exposeSchema, exposeErrors);
                        }

                        if (exposeErrors.isEmpty()) {
                            warnings.add("Validated against expose-mapping-schema.json");
                        } else {
                            // Try index mapping schema
                            JsonNode indexSchema = loadSchema(INDEX_MAPPING_SCHEMA);
                            List<String> indexErrors = new ArrayList<>();
                            if (indexSchema != null) {
                                validateAgainstSchema(metadataNode, indexSchema, indexErrors);
                            }

                            if (indexErrors.isEmpty()) {
                                warnings.add("Validated against index-mapping-schema.json");
                            } else {
                                // None validated cleanly: merge diagnostics from earlier attempts
                                errors.addAll(classErrors);
                                errors.addAll(workErrors);
                                errors.addAll(exposeErrors);
                                errors.addAll(indexErrors);
                            }
                        }
                    }
                }
            }

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

            // Additional semantic check: if this file looks like an index mapping (has mappings and references a work class),
            // ensure each mapping's jsonPath references fields that actually exist on the referenced work class (including nested types).
            if (metadataNode.has("mappings") && metadataNode.get("mappings").isArray()) {
                String className = null;
                if (metadataNode.has("workClassReference")) className = metadataNode.get("workClassReference").asText();
                else if (metadataNode.has("class")) className = metadataNode.get("class").asText();
                else if (metadataNode.has("entityType")) className = metadataNode.get("entityType").asText();

                if (className != null && !className.trim().isEmpty()) {
                    String classPath = SCHEMA_CLASS_PATH + "/" + className + ".json";
                    InputStream cis = MetadataValidationUtil.class.getClassLoader().getResourceAsStream(classPath);
                    if (cis == null) {
                        errors.add("Referenced class metadata not found: " + classPath);
                    } else {
                        String ccontent = new String(cis.readAllBytes(), StandardCharsets.UTF_8);
                        JsonNode classNode = mapper.readTree(ccontent);

                        // Build map of top-level field name -> field definition
                        // Also index by the top-segment of the field's jsonPath so mappings that use
                        // the raw jsonPath segment (e.g. "$.total") will match the class field which
                        // may be named differently (e.g. name=order_total, jsonPath=$.total).
                        Map<String, JsonNode> topFields = new HashMap<>();
                        JsonNode fieldsArray = classNode.path("fields");
                        if (fieldsArray.isArray()) {
                            for (JsonNode f : fieldsArray) {
                                if (f.has("name")) {
                                    String fname = f.get("name").asText();
                                    topFields.put(fname, f);
                                }

                                // also index by jsonPath top segment if present
                                if (f.has("jsonPath") && f.get("jsonPath").isTextual()) {
                                    String fjson = f.get("jsonPath").asText();
                                    String normalized = fjson.startsWith("$.") ? fjson.substring(2) : fjson.startsWith("$") ? fjson.substring(1) : fjson;
                                    int dotIdxFp = normalized.indexOf('.');
                                    int brIdxFp = normalized.indexOf('[');
                                    int topEndFp = normalized.length();
                                    if (dotIdxFp != -1) topEndFp = Math.min(topEndFp, dotIdxFp);
                                    if (brIdxFp != -1) topEndFp = Math.min(topEndFp, brIdxFp);
                                    String topSeg = normalized.substring(0, Math.max(0, topEndFp));
                                    if (!topSeg.isBlank()) topFields.putIfAbsent(topSeg, f);
                                }
                            }
                        }

                        for (JsonNode mapping : metadataNode.get("mappings")) {
                            String jp = mapping.path("jsonPath").asText(null);
                            if (jp == null || jp.trim().isEmpty()) continue;

                            // Normalize: remove leading "$" or "$." then split by '.' while handling array indexes
                            String remainder = jp.startsWith("$.") ? jp.substring(2) : jp.startsWith("$") ? jp.substring(1) : jp;
                            int dotIdx = remainder.indexOf('.');
                            int brIdx = remainder.indexOf('[');
                            int topEnd = remainder.length();
                            if (dotIdx != -1) topEnd = Math.min(topEnd, dotIdx);
                            if (brIdx != -1) topEnd = Math.min(topEnd, brIdx);
                            String topSegment = remainder.substring(0, topEnd);

                            // Special-case: structural map-entry tokens like "$_key" / "$_value" are not real fields
                            // and should be skipped from semantic existence checks. Also skip any top-segment that
                            // starts with an underscore (project conventions use $_* for map entries).
                            if (jp.contains("$_") || (topSegment != null && topSegment.startsWith("_"))) {
                                // skip semantic validation for structural tokens
                                continue;
                            }

                            if (!topFields.containsKey(topSegment)) {
                                errors.add("Mapping jsonPath '" + jp + "' references unknown top-level field '" + topSegment + "' in class '" + className + "'");
                            } else {
                                // If there's a nested segment (e.g. $.customer.id or $.items[0].sku) validate against nested type metadata
                                if (dotIdx != -1) {
                                    String afterTop = remainder.substring(dotIdx + 1);
                                    // extract next segment name
                                    int nextDot = afterTop.indexOf('.');
                                    int nextBr = afterTop.indexOf('[');
                                    int nextEnd = afterTop.length();
                                    if (nextDot != -1) nextEnd = Math.min(nextEnd, nextDot);
                                    if (nextBr != -1) nextEnd = Math.min(nextEnd, nextBr);
                                    String nestedSegment = afterTop.substring(0, nextEnd);

                                    JsonNode topFieldDef = topFields.get(topSegment);
                                    String topType = topFieldDef.path("type").asText(null);
                                    String topTypeLower = topType == null ? "" : topType.toLowerCase();

                                    boolean primitive = "string".equals(topTypeLower)
                                            || "integer".equals(topTypeLower)
                                            || "int".equals(topTypeLower)
                                            || "decimal".equals(topTypeLower)
                                            || "number".equals(topTypeLower)
                                            || "boolean".equals(topTypeLower)
                                            || "long".equals(topTypeLower)
                                            || "double".equals(topTypeLower)
                                            || "date".equals(topTypeLower)
                                            || "timestamp".equals(topTypeLower);

                                    if (primitive) {
                                        errors.add("Mapping jsonPath '" + jp + "' navigates into primitive field '" + topSegment + "' of class '" + className + "'");
                                    } else if (topType != null && !topType.trim().isEmpty()) {
                                        String nestedPath = SCHEMA_CLASS_PATH + "/" + topType + ".json";
                                        InputStream nis = MetadataValidationUtil.class.getClassLoader().getResourceAsStream(nestedPath);
                                        if (nis == null) {
                                            errors.add("Mapping jsonPath '" + jp + "' references nested type '" + topType + "' but metadata file not found: " + nestedPath);
                                        } else {
                                            String ncontent = new String(nis.readAllBytes(), StandardCharsets.UTF_8);
                                            JsonNode nestedClass = mapper.readTree(ncontent);
                                            Map<String, JsonNode> nestedFields = new HashMap<>();
                                            JsonNode nfArr = nestedClass.path("fields");
                                            if (nfArr.isArray()) {
                                                for (JsonNode nf : nfArr) {
                                                    if (nf.has("name")) nestedFields.put(nf.get("name").asText(), nf);
                                                }
                                            }

                                            if (!nestedFields.containsKey(nestedSegment)) {
                                                errors.add("Mapping jsonPath '" + jp + "' references unknown nested field '" + nestedSegment + "' on type '" + topType + "'");
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
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
        return loadSchema(SCHEMA_PATH);
    }

    private static JsonNode loadSchema(String schemaPointer) {
        try {
            String path = schemaPointer;
            // Normalize leading slash if present
            if (path.startsWith("/")) path = path.substring(1);
            InputStream is = MetadataValidationUtil.class.getClassLoader().getResourceAsStream(path);
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
