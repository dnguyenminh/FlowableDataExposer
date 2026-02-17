package vn.com.fecredit.complexsample.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.InputFormat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Small helper demonstrating how to validate class metadata against
 * core/src/main/resources/metadata/class-schema.json using networknt.
 */
public final class MetadataSchemaValidator {
    private static final ObjectMapper OM = new ObjectMapper();
    private static final Schema SCHEMA;

    static {
        try (InputStream is = MetadataSchemaValidator.class.getResourceAsStream("/metadata/class-schema.json")) {
            if (is == null) throw new IllegalStateException("schema resource not found");
            // Build a registry and obtain a Schema from an InputStream (networknt 3.x API)
            SCHEMA = SchemaRegistry.builder().build().getSchema(is);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private MetadataSchemaValidator() {}

    public static List<String> validate(JsonNode metadata) {
        try {
            String json = OM.writeValueAsString(metadata);
            java.util.List<com.networknt.schema.Error> errs = SCHEMA.validate(json, InputFormat.JSON);
            if (errs == null || errs.isEmpty()) return Collections.emptyList();
            List<String> out = new ArrayList<>();
            for (var e : errs) out.add(e.toString());
            return out;
        } catch (Exception ex) {
            return List.of(ex.getMessage());
        }
    }

    // Simple CLI demo: java ... MetadataSchemaValidator /path/to/Order.json
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: MetadataSchemaValidator <metadata.json>");
            System.exit(2);
        }
        Path p = Path.of(args[0]);
        JsonNode node = OM.readTree(Files.newInputStream(p));
        var errors = validate(node);
        if (errors.isEmpty()) {
            System.out.println("OK: metadata is valid");
            System.exit(0);
        } else {
            System.out.println("INVALID: found " + errors.size() + " issues:");
            errors.forEach(System.out::println);
            System.exit(1);
        }
    }
}
