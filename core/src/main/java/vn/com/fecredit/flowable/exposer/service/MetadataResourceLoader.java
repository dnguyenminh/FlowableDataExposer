package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import jakarta.annotation.PostConstruct;
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
    private final ObjectMapper mapper = new ObjectMapper();
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
            MetadataDefinition def = mapper.readValue(txt, MetadataDefinition.class);
            if (def == null || def._class == null) return;
            if (Boolean.TRUE.equals(def.deprecated) || (def.migratedToModule != null && !def.migratedToModule.isBlank())) {
                log.debug("Skipping migrated/ deprecated metadata file: {} -> class={} migratedTo={}", r.getFilename(), def._class, def.migratedToModule);
                return;
            }
            fileDefs.put(def._class, def);
            log.debug("Loaded metadata file: {} -> class={} entityType={}", r.getFilename(), def._class, def.entityType);
        } catch (Exception ex) {
            // ignore malformed/partial files and continue
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
