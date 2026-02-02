package vn.com.fecredit.flowable.exposer.service.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import java.io.File;
import java.nio.file.Files;

/**
 * Small CLI utility to validate metadata JSON and JsonPath expressions.
 * Usage: MetadataTool --validate <file>
 */
public class MetadataTool {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !"--validate".equals(args[0])) {
            System.out.println("Usage: MetadataTool --validate <file>");
            System.exit(2);
        }
        File f = new File(args[1]);
        String txt = Files.readString(f.toPath());
        ObjectMapper om = new ObjectMapper();
        MetadataDefinition def = om.readValue(txt, MetadataDefinition.class);
        if (def._class == null) throw new IllegalArgumentException("missing 'class' field");
        if (def.entityType == null) throw new IllegalArgumentException("missing 'entityType' field");
        if (def.mappings != null) {
            for (MetadataDefinition.FieldMapping fm : def.mappings) {
                if (fm.column == null || fm.jsonPath == null) throw new IllegalArgumentException("mapping must have column and jsonPath");
                // if exportToPlain is requested, ensure plainColumn is present and looks like an identifier
                if (Boolean.TRUE.equals(fm.exportToPlain)) {
                    if (fm.plainColumn == null || !fm.plainColumn.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                        throw new IllegalArgumentException("exportToPlain requires a valid plainColumn for column=" + fm.column);
                    }
                }
                // compile JsonPath to catch syntax errors
                try {
                    JsonPath.compile(fm.jsonPath);
                } catch (Exception ex) {
                    throw new IllegalArgumentException("invalid JsonPath for column=" + fm.column + ": " + ex.getMessage(), ex);
                }
            }
        }
        System.out.println("OK: " + f.getName());
    }
}
