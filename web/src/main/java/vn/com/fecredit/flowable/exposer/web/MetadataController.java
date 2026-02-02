package vn.com.fecredit.flowable.exposer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository;
import vn.com.fecredit.flowable.exposer.service.MetadataResolver;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * Management endpoints for metadata: validation, field-check and admin import.
 *
 * <p>Endpoints are used by the metadata UI and by CI/automation to validate
 * and apply mapping changes without requiring a full deployment.</p>
 */
@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MetadataResolver resolver;
    private final SysExposeClassDefRepository repo;
    private final org.springframework.context.ApplicationContext appCtx;

    public MetadataController(MetadataResolver resolver, SysExposeClassDefRepository repo, org.springframework.context.ApplicationContext appCtx) {
        this.resolver = resolver;
        this.repo = repo;
        this.appCtx = appCtx;
    }

    /**
     * Validate a metadata JSON payload for syntactic correctness and required
     * properties. Returns 200 + {valid:true} when the payload is acceptable.
     */
    @PostMapping("/validate")
    public ResponseEntity<?> validate(@RequestBody JsonNode body) {
        try {
            MetadataDefinition def = mapper.treeToValue(body, MetadataDefinition.class);
            if (def._class == null || def.entityType == null) return ResponseEntity.badRequest().body(Map.of("error", "missing class or entityType"));
            if (def.mappings != null) {
                for (var m : def.mappings) {
                    if (m.column == null || m.jsonPath == null) return ResponseEntity.badRequest().body(Map.of("error", "mapping must have column and jsonPath"));
                    JsonPath.compile(m.jsonPath);
                }
            }
            return ResponseEntity.ok(Map.of("valid", true));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "message", ex.getMessage()));
        }
    }

    @PostMapping("/field-check")
    public ResponseEntity<?> fieldCheck(@RequestBody JsonNode body) {
        try {
            String className = body.path("class").asText();
            JsonNode sample = body.path("sampleBlob");
            JsonNode mappingsNode = body.path("mappings");
            Map<String, String> mappings = new HashMap<>();
            if (mappingsNode != null && mappingsNode.isArray()) {
                for (JsonNode n : mappingsNode) mappings.put(n.path("column").asText(), n.path("jsonPath").asText());
            } else {
                mappings = resolver.mappingsFor(className);
            }
            Map<String, Object> out = new HashMap<>();
            String json = mapper.writeValueAsString(sample);
            for (var e : mappings.entrySet()) {
                try {
                    Object v = JsonPath.read(json, e.getValue());
                    out.put(e.getKey(), v);
                } catch (Exception ex) {
                    out.put(e.getKey(), Map.of("error", ex.getMessage()));
                }
            }
            return ResponseEntity.ok(Map.of("extracted", out));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody JsonNode body) {
        try {
            MetadataDefinition def = mapper.treeToValue(body, MetadataDefinition.class);
            if (def._class == null || def.entityType == null) return ResponseEntity.badRequest().body(Map.of("error", "missing class or entityType"));
            vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef ent = new vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef();
            ent.setClassName(def._class);
            ent.setEntityType(def.entityType);
            ent.setJsonDefinition(mapper.writeValueAsString(body));
            ent.setVersion(def.version == null ? 1 : def.version);
            ent.setEnabled(def.enabled == null ? true : def.enabled);
            repo.save(ent);
            resolver.evict(def.entityType != null ? def.entityType : def._class);
            return ResponseEntity.ok(Map.of("imported", def._class, "version", ent.getVersion()));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/reindex/{entityType}")
    public ResponseEntity<?> reindex(@PathVariable String entityType) {
        try {
            Object bean = null;
            if (appCtx.containsBean("caseDataWorker")) bean = appCtx.getBean("caseDataWorker");
            else {
                for (String n : appCtx.getBeanDefinitionNames()) {
                    if (n.toLowerCase().contains("casedataworker")) { bean = appCtx.getBean(n); break; }
                }
            }
            if (bean == null) throw new IllegalStateException("CaseDataWorker bean not available");
            java.lang.reflect.Method m = bean.getClass().getMethod("reindexAll", String.class);
            m.invoke(bean, entityType);
            return ResponseEntity.accepted().build();
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(Map.of("error", ex.getMessage()));
        }
    }
}
