package vn.com.fecredit.complexsample.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.com.fecredit.complexsample.service.MetadataDdlGenerator;
import vn.com.fecredit.complexsample.service.metadata.MetadataDefinition;
import vn.com.fecredit.complexsample.entity.SysExposeIndexJob;
import vn.com.fecredit.complexsample.repository.SysExposeIndexJobRepository;
import vn.com.fecredit.complexsample.service.MetadataResolver;
import com.networknt.schema.InputFormat;

import java.util.Map;

@RestController
@RequestMapping("/api/index-job")
public class IndexJobController {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SysExposeIndexJobRepository repo;
    private final MetadataResolver resolver;

    public IndexJobController(SysExposeIndexJobRepository repo, MetadataResolver resolver) {
        this.repo = repo;
        this.resolver = resolver;
    }

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody Map<String,Object> body) {
        // Validate mappings against JSON Schema and generate preview DDL for plain exports
        try {
            // Load schema from classpath
            var schemaStream = getClass().getResourceAsStream("/metadata/index-mapping-schema.json");
            if (schemaStream == null) throw new IllegalStateException("schema resource not found");
            com.networknt.schema.Schema schema = com.networknt.schema.SchemaRegistry.builder().build().getSchema(schemaStream);
            com.fasterxml.jackson.databind.JsonNode payload = mapper.valueToTree(body);
            String payloadStr = mapper.writeValueAsString(payload);
            java.util.List<com.networknt.schema.Error> errors = schema.validate(payloadStr, InputFormat.JSON);
            if (errors != null && !errors.isEmpty()) {
                java.util.List<String> diags = new java.util.ArrayList<>();
                for (var e : errors) diags.add(e.toString());
                return ResponseEntity.badRequest().body(Map.of("valid", false, "diagnostics", diags));
            }

            // generate plain DDL for any mapping with exportToPlain or exportDest contains "plain"
            java.util.List<String> ddls = new java.util.ArrayList<>();
            var mappings = payload.path("mappings");
            for (var m : mappings) {
                boolean exportPlain = false;
                if (m.has("exportDest")) {
                    for (var v : m.withArray("exportDest")) if (v.asText().equalsIgnoreCase("plain")) exportPlain = true;
                }
                if (m.has("exportToPlain") && m.get("exportToPlain").asBoolean(false)) exportPlain = true;
                if (exportPlain) {
                    String plainCol = m.has("plainColumn") ? m.get("plainColumn").asText() : m.path("column").asText(null);
                    String type = m.has("type") ? m.get("type").asText() : null;
                    MetadataDefinition.FieldMapping fm = new MetadataDefinition.FieldMapping();
                    fm.plainColumn = plainCol;
                    fm.column = m.path("column").asText(null);
                    fm.type = type;
                    fm.nullable = m.path("nullable").asBoolean(true);
                    // table name convention: case_plain_<entityType lower>
                    String table = "case_plain_" + (payload.has("entityType") ? payload.get("entityType").asText().toLowerCase() : (payload.has("class") ? payload.get("class").asText().toLowerCase() : "unknown"));
                    ddls.addAll(MetadataDdlGenerator.generateAddColumnsForMappings(table, java.util.List.of(fm)));
                }
            }

            return ResponseEntity.ok(Map.of("valid", true, "ddl", ddls));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/create")
    public ResponseEntity<?> create(@RequestBody Map<String,Object> body) {
        try {
            String entityType = (String) body.get("entityType");
            String className = (String) body.get("class");
            String mappingsJson = mapper.writeValueAsString(body.get("mappings"));
            SysExposeIndexJob job = new SysExposeIndexJob();
            job.setEntityType(entityType != null ? entityType : className);
            job.setClassName(className);
            job.setMappings(mappingsJson);
            job.setDryRun((Boolean) body.getOrDefault("dryRun", Boolean.TRUE));
            job.setChunkSize((Integer) body.getOrDefault("chunkSize", 1000));
            repo.save(job);
            return ResponseEntity.ok(Map.of("jobId", job.getId(), "status", job.getStatus()));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/run/{jobId}")
    public ResponseEntity<?> run(@PathVariable Long jobId) {
        // Trigger asynchronous run via CaseDataWorker.reindexAll or job runner
        // Implementation note: controller should enqueue or invoke worker bean; keeping placeholder
        try {
            // TODO: find CaseDataWorker and invoke reindex with jobId context
            return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "ENQUEUED"));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<?> get(@PathVariable Long jobId) {
        return repo.findById(jobId).map(j -> ResponseEntity.ok(j)).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
