package vn.com.fecredit.flowable.exposer.service.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Utility for validating expose-mapping JSON files against a lightweight schema
 * and ensuring mappings reference existing class fields under metadata/classes.
 * <p>
 * This class provides a small, dependency-free validator used in tests and
 * tooling to perform pragmatic checks (presence of required fields, existence
 * of referenced work class, and basic jsonPath-to-field matching).
 */
public class ExposeMappingValidationUtil {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String SCHEMA_PATH = "metadata/expose-mapping-schema.json";
    private static final String CLASSES_PATH_PREFIX = "metadata/classes/";

    /**
     * Result returned by {@link #validate(String)}.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;

        /**
         * Construct a ValidationResult.
         *
         * @param valid  whether the validation succeeded (no errors)
         * @param errors list of error messages (may be empty)
         */
        public ValidationResult(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors != null ? errors : new ArrayList<>();
        }

        /**
         * @return true when validation produced no errors
         */
        public boolean isValid() { return valid; }

        /**
         * @return immutable list of error messages (may be empty)
         */
        public List<String> getErrors() { return errors; }

        @Override
        public String toString() { return "ValidationResult{valid="+valid+", errors="+errors+"}"; }
    }

    /**
     * Validate an expose mapping resource on the classpath.
     * <p>
     * The validation performs these checks:
     * <ol>
     *   <li>parses JSON</li>
     *   <li>applies anyOf presence checks from the schema</li>
     *   <li>ensures workClassReference points to an existing class resource</li>
     *   <li>ensures mappings array exists and each mapping has a jsonPath</li>
     *   <li>when workClassReference is present, verifies mapping jsonPaths
     *       are compatible with fields declared in the referenced class</li>
     * </ol>
     *
     * @param resourcePath classpath resource path (e.g. metadata/exposes/foo.json)
     * @return ValidationResult describing any problems found
     */
    public static ValidationResult validate(String resourcePath) {
        List<String> errors = new ArrayList<>();
        JsonNode root = loadJsonResource(resourcePath, errors);
        if (root == null) return new ValidationResult(false, errors);

        JsonNode schema = loadSchema();
        validateAnyOf(root, schema, errors);
        String workRef = validateWorkClassReference(root, errors);
        validateMappings(root, errors);
        if (workRef != null && !workRef.isBlank()) {
            validateMappingsAgainstClass(root, workRef, errors);
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * Load a JSON resource from the classpath and parse it.
     *
     * @param resourcePath path to classpath resource
     * @param errors       list to append parse/load errors
     * @return parsed JsonNode or null on error
     */
    private static JsonNode loadJsonResource(String resourcePath, List<String> errors) {
        try (InputStream is = ExposeMappingValidationUtil.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) { errors.add("Expose mapping file not found on classpath: " + resourcePath); return null; }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return mapper.readTree(content);
        } catch (Exception ex) {
            errors.add("Failed to parse expose mapping: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Load the lightweight schema used for anyOf checks.
     *
     * @return schema JsonNode or null when not available
     */
    private static JsonNode loadSchema() {
        try (InputStream sis = ExposeMappingValidationUtil.class.getClassLoader().getResourceAsStream(SCHEMA_PATH)) {
            if (sis == null) return null;
            String s = new String(sis.readAllBytes(), StandardCharsets.UTF_8);
            return mapper.readTree(s);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Validate anyOf required combinations declared in the schema.
     *
     * @param root   parsed expose mapping
     * @param schema schema node (may be null)
     * @param errors append validation errors
     */
    private static void validateAnyOf(JsonNode root, JsonNode schema, List<String> errors) {
        if (schema == null || !schema.has("anyOf")) return;
        boolean anyOk = false;
        for (JsonNode any : schema.get("anyOf")) {
            if (!any.has("required")) continue;
            boolean ok = true;
            for (JsonNode req : any.get("required")) { if (!root.has(req.asText())) { ok = false; break; } }
            if (ok) { anyOk = true; break; }
        }
        if (!anyOk) errors.add("Expose mapping does not satisfy anyOf requirements (missing required combination)");
    }

    /**
     * Ensure workClassReference points to an existing class resource under metadata/classes.
     *
     * @param root   parsed expose mapping
     * @param errors append validation errors
     * @return the workClassReference value or null if not present
     */
    private static String validateWorkClassReference(JsonNode root, List<String> errors) {
        if (!root.has("workClassReference")) return null;
        String ref = root.path("workClassReference").asText();
        if (ref == null || ref.isBlank()) { errors.add("workClassReference is empty"); return ref; }
        String path = CLASSES_PATH_PREFIX + ref + ".json";
        try (InputStream cls = ExposeMappingValidationUtil.class.getClassLoader().getResourceAsStream(path)) {
            if (cls == null) errors.add("Referenced work class not found on classpath: " + path);
        } catch (Exception ex) {
            errors.add("Failed to validate workClassReference: " + ex.getMessage());
        }
        return ref;
    }

    /**
     * Basic checks for presence and shape of the mappings array.
     *
     * @param root   parsed expose mapping
     * @param errors append validation errors
     */
    private static void validateMappings(JsonNode root, List<String> errors) {
        if (!root.has("mappings") || !root.get("mappings").isArray()) { errors.add("Expose mapping must declare a non-empty 'mappings' array"); return; }
        int idx = 0;
        for (JsonNode item : root.get("mappings")) {
            if (!item.has("jsonPath")) { errors.add("mappings[" + idx + "]: missing required field 'jsonPath'"); }
            else if (item.get("jsonPath").isTextual() && item.get("jsonPath").asText().trim().isEmpty()) {
                errors.add("mappings[" + idx + "]: 'jsonPath' is empty");
            }
            idx++;
        }
    }

    /**
     * Validate each mapping jsonPath against fields declared in the referenced class.
     * This method applies normalization and token-based heuristics to accept
     * common forms such as array indices and camelCase field names.
     *
     * @param root    parsed expose mapping
     * @param workRef referenced work class (simple name)
     * @param errors  append validation errors
     */
    private static void validateMappingsAgainstClass(JsonNode root, String workRef, List<String> errors) {
        JsonNode cls = loadJsonResource(CLASSES_PATH_PREFIX + workRef + ".json", new ArrayList<>());
        if (cls == null) return; // already reported by workClassReference
        Set<String> fieldJsonPaths = extractFieldJsonPathsFromClass(cls);
        Set<String> normalizedFields = new HashSet<>();
        for (String f : fieldJsonPaths) normalizedFields.add(normalizePath(f));

        int idx = 0;
        for (JsonNode item : root.get("mappings")) {
            String mp = item.has("jsonPath") && item.get("jsonPath").isTextual() ? item.get("jsonPath").asText().trim() : null;
            if (mp == null) { idx++; continue; }
            String normMp = normalizePath(mp);
            boolean ok = false;

            // direct or prefix match
            for (String nf : normalizedFields) {
                if (normMp.equals(nf) || normMp.startsWith(nf + ".") || normMp.startsWith(nf + "[") || nf.equals(normMp)) { ok = true; break; }
            }

            // fall back: match by first path segment
            if (!ok) {
                String firstSeg = normMp.split("\\.")[0];
                for (String nf : normalizedFields) {
                    if (nf.equals(firstSeg) || nf.startsWith(firstSeg + ".") || nf.startsWith(firstSeg)) { ok = true; break; }
                }
            }

            // fallback: token overlap (handles array index and camelCase)
            if (!ok) {
                var mapTokens = tokensOf(normMp);
                for (String nf : normalizedFields) {
                    var fieldTokens = tokensOf(nf);
                    for (String t : mapTokens) {
                        if (fieldTokens.contains(t)) { ok = true; break; }
                    }
                    if (ok) break;
                }
            }

            if (!ok) {
                errors.add("mappings[" + idx + "]: jsonPath '" + mp + "' does not match any field in work class '" + workRef + "' (normalizedMapping='" + normMp + "' normalizedFields=" + normalizedFields + ")");
            }
            idx++;
        }
    }

    /**
     * Extract jsonPath values for fields declared in a class metadata resource.
     *
     * @param cls parsed class metadata
     * @return set of jsonPath strings (may be empty)
     */
    private static Set<String> extractFieldJsonPathsFromClass(JsonNode cls) {
        Set<String> out = new HashSet<>();
        if (cls == null || !cls.has("fields") || !cls.get("fields").isArray()) return out;
        for (JsonNode f : cls.get("fields")) {
            if (f.has("jsonPath") && f.get("jsonPath").isTextual()) out.add(f.get("jsonPath").asText());
            else if (f.has("name") && f.get("name").isTextual()) out.add("$." + f.get("name").asText());
        }
        return out;
    }

    /**
     * Normalize a jsonPath by removing leading '$' and array indices like [0].
     * Normalization yields a compact dot-separated path used for comparison.
     *
     * @param p input jsonPath
     * @return normalized path string
     */
    private static String normalizePath(String p) {
        if (p == null) return "";
        String s = p.trim();
        if (s.startsWith("$.")) s = s.substring(2);
        else if (s.startsWith("$")) s = s.substring(1);
        // remove array indices like [0]
        s = s.replaceAll("\\[\\d+\\]", "");
        // collapse consecutive dots
        s = s.replaceAll("\\.+", ".");
        return s;
    }

    /**
     * Tokenize a normalized path into lower-case components. Splits camelCase
     * and non-alphanumeric separators into tokens.
     *
     * @param s normalized path
     * @return set of tokens
     */
    private static java.util.Set<String> tokensOf(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (s == null || s.isBlank()) return out;
        String t = s.replaceAll("([a-z])([A-Z])", "$1.$2");
        t = t.replaceAll("[^A-Za-z0-9]+", ".").toLowerCase();
        for (String part : t.split("\\.")) {
            if (!part.isBlank()) out.add(part);
        }
        return out;
    }
}
