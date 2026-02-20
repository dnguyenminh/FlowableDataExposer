package vn.com.fecredit.flowable.exposer.service.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class IndexDefinition {
    @JsonProperty("class")
    public String _class;
    public String workClassReference;
    public String description;
    public String table;
    public String jsonPath;
    public List<IndexField> mappings;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IndexField {
        public String jsonPath;
        public String plainColumn;
        public String type;
        public Boolean nullable = true;
    }
}
