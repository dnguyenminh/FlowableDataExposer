package vn.com.fecredit.flowable.exposer.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MetadataResolver {
    // For demo purposes we hard-code mappings for an "Order" class
    // key -> JsonPath
    public Map<String, String> mappingsFor(String className) {
        Map<String, String> m = new LinkedHashMap<>();
        if ("Order".equals(className)) {
            m.put("total_amount", "$.total");
            m.put("item_1_id", "$.items[0].id");
            m.put("color_attr", "$.params.color");
        }
        return m;
    }
}
