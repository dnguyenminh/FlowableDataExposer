package vn.com.fecredit.flowable.exposer.service.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * POJO that represents the canonical metadata JSON used to map JSON payloads
 * into index/plain columns and to drive the metadata UI.
 *
 * <p>Fields are intentionally public to simplify Jackson deserialization
 * and make test fixtures concise.</p>
 */
public class MetadataDefinition {
    @JsonProperty("class")
    public String _class; // 'class' is a reserved word in Java so JSON maps to _class
    public String entityType;
    public String parent;
    /** Optional mixins (additional class defs to be merged in declared order). */
    public java.util.List<String> mixins;
    public Integer version = 1;
    public Boolean enabled = true;
    /** Mark this canonical file as deprecated so runtime can ignore it (migration path). */
    public Boolean deprecated = false;
    /** If present, indicates the module that now owns this canonical definition (e.g. "web"). */
    public String migratedToModule;
    public String description;
    // optional class-level jsonPath (e.g. "$.customer") to allow class-scoped mappings
    public String jsonPath;
    public List<FieldMapping> mappings;

    // Optional structured field declarations to support annotator
    public List<FieldDef> fields;

    public static class FieldMapping {
        public String column;
        public String jsonPath;
        public String type;
        public Boolean nullable = true;
        @JsonProperty("default")
        public Object defaultValue;
        public Boolean index = false;
        public Integer order;
        public Boolean remove = false;

        // New "annotations" for export behaviour
        // Whether this field should also be exported into a normalized "case plain" table
        public Boolean exportToPlain = false;
        // If present, override target column name in the plain table
        public String plainColumn;
        // Additional export destinations: e.g. ["idx","plain"]
        public List<String> exportDest;
        // PII / masking hints
        public Boolean sensitive = false;
        public String piiMask; // e.g. "MASK_LAST_4"

        // optional: mapping belongs to a nested class (class-scoped mapping)
        @JsonProperty("class")
        public String klass;

        // for array mappings, optional index
        public Integer arrayIndex;

        // Provenance (populated by MetadataResolver when merging)
        public String sourceClass;   // e.g. "Order", "MixinA"
        public String sourceKind;    // "file" or "db"
        public String sourceModule;  // e.g. "core" or "web" (optional)
        public String sourceLocation; // optional human-friendly location (filename)

    }

    // Field-level defs for annotator
    public static class FieldDef {
        public String name; // field name in JSON/vars
        // primary class hint: allow both explicit "className" and legacy "type" in JSON files
        public String className; // e.g. "Customer"
        public String type; // legacy alias for className
        // elementClass/elementType hints for collections / maps
        public String elementClass; // for arrays/lists e.g. "Item"
        public String elementType; // legacy alias for elementClass
        // array/list indicators (helpful when only "type" is provided)
        public Boolean isArray;
        public Boolean isList;
    }
}
