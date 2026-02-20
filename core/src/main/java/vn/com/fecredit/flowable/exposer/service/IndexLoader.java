package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.service.metadata.IndexDefinition;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class IndexLoader {
    private static final Logger log = LoggerFactory.getLogger(IndexLoader.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, IndexDefinition> indexByClass = new HashMap<>();
    private final Map<String, IndexDefinition> indexByTable = new HashMap<>();

    @PostConstruct
    public void init() {
        loadIndexFiles();
    }

    private void loadIndexFiles() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:metadata/indices/*.json");
            log.debug("IndexLoader found {} index files", resources.length);
            for (Resource r : resources) {
                try (InputStream is = r.getInputStream()) {
                    String txt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    IndexDefinition def = mapper.readValue(txt, IndexDefinition.class);
                    if (def == null || (def._class == null && def.workClassReference == null && def.table == null)) {
                        log.warn("Skipping malformed index file {}", r.getFilename());
                        continue;
                    }
                    String key = def._class != null ? def._class : def.workClassReference;
                    if (key != null) indexByClass.put(key, def);
                    if (def.table != null) indexByTable.put(def.table, def);
                    log.info("Loaded index definition {} -> table={}", key, def.table);
                } catch (Exception ex) {
                    log.warn("Failed to parse index resource {}: {}", r.getFilename(), ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.debug("IndexLoader.loadIndexFiles failed: {}", ex.getMessage());
        }
    }

    public Optional<IndexDefinition> findByClass(String className) {
        if (className == null) return Optional.empty();
        IndexDefinition def = indexByClass.get(className);
        if (def != null) return Optional.of(def);
        return indexByClass.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(className)).map(Map.Entry::getValue).findFirst();
    }

    public Optional<IndexDefinition> findByTable(String table) {
        if (table == null) return Optional.empty();
        IndexDefinition def = indexByTable.get(table);
        if (def != null) return Optional.of(def);
        return indexByTable.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(table)).map(Map.Entry::getValue).findFirst();
    }

    public Collection<IndexDefinition> all() { return Collections.unmodifiableCollection(indexByClass.values()); }
}
