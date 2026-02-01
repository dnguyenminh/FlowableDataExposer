package vn.com.fecredit.flowable.exposer.service.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class MetadataDefinition {
    @JsonProperty("class")
    public String _class; // 'class' is a reserved word in Java so JSON maps to _class
    public String entityType;
    public String parent;
    public Integer version = 1;
    public Boolean enabled = true;
    public String description;
    public List<FieldMapping> mappings;

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
    }
}
