package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads file-backed metadata definitions from classpath and provides simple lookup helpers.
 * Extracted from MetadataResolver to keep that class focused and smaller.
 */
@Component
public class MetadataResourceLoader {

    private static final Logger log = LoggerFactory.getLogger(MetadataResourceLoader.class);
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, MetadataDefinition> fileDefs = new HashMap<>();

    @PostConstruct
    public void init() {
        loadFileMetadata();
    }

    // Load canonical metadata from classpath: src/main/resources/metadata/**/*.json
    private void loadFileMetadata() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:metadata/**/*.json");
            log.debug("MetadataResourceLoader found {} metadata files on classpath", resources.length);
            for (Resource r : resources) {
                parseAndRegisterResource(r);
            }
        } catch (Exception e) {
            log.debug("MetadataResourceLoader.loadFileMetadata failed: {}", e.getMessage());
        }
    }

    private void parseAndRegisterResource(Resource r) {
        try (InputStream is = r.getInputStream()) {
            String txt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            
            // Safely get path for validation
            String p = r.getFilename();
            try {
                if (r.getURI() != null && r.getURI().getPath() != null) {
                    p = r.getURI().getPath();
                }
            } catch (Exception ignored) {
                // fallback to filename if getURI fails
            }
            System.out.println("Processing resource: " + r.getFilename() + ", path: " + p);

            // Validate file by path: classes/ -> use class-schema.json, exposes/ -> use expose-mapping-schema.json, indices/ -> use index-mapping-schema.json
            boolean validated = true;
            try {
                if (p.contains("/classes/")) {
                    // validate against class-schema.json
                    // lightweight validation: check contains "class" and "fields" or "mappings"
                    if (!txt.contains("\"class\"") || !(txt.contains("\"fields\"") || txt.contains("\"mappings\"") )) {
                        validated = false;
                    }
                } else if (p.contains("/exposes/")) {
                    if (!txt.contains("\"jsonPath\"") && !txt.contains("\"mappings\"")) validated = false;
                } else if (p.contains("/indices/")) {
                    if (!txt.contains("\"mappings\"")) validated = false;
                }
            } catch (Exception ve) {
                // schema read failed — allow parsing but log
                log.warn("Failed to read embedded schema for resource {}: {}", r.getFilename(), ve.getMessage());
            }

            if (!validated) {
                System.out.println("Skipping due to validation failure: " + r.getFilename());
                log.warn("Skipping metadata file due to basic validation failure: {}", r.getFilename());
                return;
            }

            // Parse JSON tree so we can infer common aliases (workClassReference/workClass) for the canonical 'class' value
            var root = mapper.readTree(txt);
            String inferredClass = null;
            if (root.has("class")) {
                inferredClass = root.get("class").asText();
            } else if (root.has("workClassReference")) {
                inferredClass = root.get("workClassReference").asText();
            } else if (root.has("workClass")) {
                inferredClass = root.get("workClass").asText();
            }

            MetadataDefinition def = mapper.treeToValue(root, MetadataDefinition.class);
            System.out.println("  Deserialized parent: " + (def != null ? def.parent : "<null>"));

            if (def == null) {
                System.out.println("Skipping due to null def: " + r.getFilename());
                return;
            }

            // Backfill _class when not provided explicitly but referenced via workClassReference/workClass
            if (def._class == null && inferredClass != null && !inferredClass.isBlank()) {
                def._class = inferredClass;
                System.out.println("  Inferred class '" + inferredClass + "' for resource: " + r.getFilename());
            }

            if (def._class == null) {
                System.out.println("Skipping due to null def._class: " + r.getFilename());
                return;
            }

            if (Boolean.TRUE.equals(def.deprecated) || (def.migratedToModule != null && !def.migratedToModule.isBlank())) {
                System.out.println("Skipping deprecated/migrated file: " + r.getFilename());
                log.debug("Skipping migrated/ deprecated metadata file: {} -> class={} migratedTo={}", r.getFilename(), def._class, def.migratedToModule);
                return;
            }

            // If we already have a file-backed definition for this class, merge mappings instead of replacing
            MetadataDefinition existing = fileDefs.get(def._class);
            if (existing != null) {
                System.out.println("Merging metadata file into existing class def: " + r.getFilename() + " -> class=" + def._class);
                // prefer existing tableName unless this resource defines it
                if ((existing.tableName == null || existing.tableName.isBlank()) && def.tableName != null) existing.tableName = def.tableName;
                if (existing.entityType == null || existing.entityType.isBlank()) existing.entityType = def.entityType;
                if (def.mappings != null && !def.mappings.isEmpty()) {
                    if (existing.mappings == null) existing.mappings = new java.util.ArrayList<>();
                    existing.mappings.addAll(def.mappings);
                }
                if (def.fields != null && !def.fields.isEmpty()) {
                    if (existing.fields == null) existing.fields = new java.util.ArrayList<>();
                    existing.fields.addAll(def.fields);
                }
                // leave other provenance as-is; update map
                fileDefs.put(existing._class, existing);
            } else {
                fileDefs.put(def._class, def);
            }
            System.out.println("Successfully loaded metadata file: " + r.getFilename() + " -> class=" + def._class);
            log.debug("Loaded metadata file: {} -> class={} entityType={}", r.getFilename(), def._class, def.entityType);
        } catch (Exception ex) {
            // ignore malformed/partial files and continue
            log.warn("Failed to parse metadata resource {}: {}", r.getFilename(), ex.getMessage());
        }
    }

    public Optional<MetadataDefinition> getByClass(String className) {
        if (className == null) return Optional.empty();
        MetadataDefinition md = fileDefs.get(className);
        if (md != null) return Optional.of(md);
        // case-insensitive lookup
        return fileDefs.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(className))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    public Optional<MetadataDefinition> findByEntityTypeOrClassCandidates(List<String> candidates) {
        if (candidates == null) return Optional.empty();
        return fileDefs.values().stream()
                .filter(d -> candidates.stream().anyMatch(c -> (d.entityType != null && c.equalsIgnoreCase(d.entityType)) || (d._class != null && c.equalsIgnoreCase(d._class))))
                .findFirst();
    }

    public Collection<MetadataDefinition> all() { return Collections.unmodifiableCollection(fileDefs.values()); }
}
