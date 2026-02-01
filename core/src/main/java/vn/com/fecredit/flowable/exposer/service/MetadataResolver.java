package vn.com.fecredit.flowable.exposer.service;

// ...existing code...

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class MetadataResolver {

    private static final Logger log = LoggerFactory.getLogger(MetadataResolver.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, MetadataDefinition> fileDefs = new HashMap<>();
    private final SysExposeClassDefRepository repo;
    private final Cache<String, Map<String, MetadataDefinition.FieldMapping>> resolvedCache;

    @Autowired
    public MetadataResolver(SysExposeClassDefRepository repo) {
        this.repo = repo;
        this.resolvedCache = Caffeine.newBuilder()
                .maximumSize(1024)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
        loadFileMetadata();
    }

    // Load canonical metadata from classpath: src/main/resources/metadata/*.json
    private void loadFileMetadata() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            // load any metadata json files under metadata/ (including metadata/classes/)
            Resource[] resources = resolver.getResources("classpath*:metadata/**/*.json");
            log.debug("MetadataResolver found {} metadata files on classpath", resources.length);
            for (Resource r : resources) {
                try (InputStream is = r.getInputStream()) {
                    String txt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    MetadataDefinition def = mapper.readValue(txt, MetadataDefinition.class);
                    if (def._class == null) continue;
                    // allow file-backed class defs that may not have entityType (nested class defs)
                    fileDefs.put(def._class, def);
                    log.debug("Loaded metadata file: {} -> class={} entityType={}", r.getFilename(), def._class, def.entityType);
                }
            }
        } catch (Exception e) {
            // non-fatal for runtime; tests will catch missing files
            log.debug("MetadataResolver.loadFileMetadata failed: {}", e.getMessage());
        }
    }

    /**
     * Backwards-compatible: column -> jsonPath
     */
    public Map<String, String> mappingsFor(String classOrEntityType) {
        return mappingsMetadataFor(classOrEntityType).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().jsonPath, (a,b)->b, LinkedHashMap::new));
    }

    /**
     * New API: return full FieldMapping objects (preserves exportToPlain, plainColumn, etc.)
     */
    public Map<String, MetadataDefinition.FieldMapping> mappingsMetadataFor(String classOrEntityType) {
        return resolvedCache.get(classOrEntityType, k -> resolveAndFlatten(classOrEntityType));
    }

    // Change resolveAndFlatten to return merged FieldMapping map instead of only jsonPath
    private Map<String, MetadataDefinition.FieldMapping> resolveAndFlatten(String classOrEntityType) {
        try {
            // 1) prefer DB-backed (by entityType)
            Optional<vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef> dbDef = repo.findLatestEnabledByEntityType(classOrEntityType);
            MetadataDefinition md = null;
            if (dbDef.isPresent()) {
                md = mapper.readValue(dbDef.get().getJsonDefinition(), MetadataDefinition.class);
            } else {
                // try by class name
                md = fileDefs.get(classOrEntityType);
                if (md == null) {
                    md = fileDefs.values().stream()
                            .filter(d -> classOrEntityType.equals(d.entityType) || classOrEntityType.equals(d._class))
                            .findFirst().orElse(null);
                }
            }
            if (md == null) return Collections.emptyMap();

            List<MetadataDefinition> chain = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            MetadataDefinition cur = md;
            while (cur != null && cur._class != null && !seen.contains(cur._class)) {
                chain.add(cur);
                seen.add(cur._class);
                if (cur.parent != null) {
                    Optional<vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef> pdb = repo.findByClassNameOrderByVersionDesc(cur.parent).stream().findFirst();
                    if (pdb.isPresent()) {
                        cur = mapper.readValue(pdb.get().getJsonDefinition(), MetadataDefinition.class);
                    } else {
                        cur = fileDefs.get(cur.parent);
                    }
                } else {
                    cur = null;
                }
            }
            Collections.reverse(chain);

            Map<String, MetadataDefinition.FieldMapping> merged = new LinkedHashMap<>();
            for (MetadataDefinition def : chain) {
                if (def.mappings == null) continue;
                for (MetadataDefinition.FieldMapping fm : def.mappings) {
                    if (fm.remove != null && fm.remove) {
                        merged.remove(fm.column);
                        continue;
                    }
                    // If mapping references a nested class (klass), resolve that class's jsonPath and adjust the mapping jsonPath accordingly
                    if (fm.klass != null && !fm.klass.isBlank()) {
                        MetadataDefinition nested = fileDefs.get(fm.klass);
                        if (nested == null) {
                            // try to find among chain
                            nested = chain.stream().filter(d -> fm.klass.equals(d._class)).findFirst().orElse(null);
                        }
                        if (nested != null && nested.jsonPath != null && !nested.jsonPath.isBlank()) {
                            // combine nested.jsonPath and fm.jsonPath (which is relative to the class)
                            String base = nested.jsonPath.trim();
                            String rel = fm.jsonPath == null ? "" : fm.jsonPath.trim();
                            // handle arrayIndex on mapping: if provided, and base ends with ']', append index access
                            if (fm.arrayIndex != null) {
                                // if base already points to an array, append [index]
                                if (!base.endsWith("]")) base = base + "[" + fm.arrayIndex + "]";
                            }
                            // if rel starts with '$' or '.', strip leading '$' to avoid duplicate root token
                            if (rel.startsWith("$")) rel = rel.substring(1);
                            if (rel.startsWith(".")) rel = rel.substring(1);
                            // join ensuring a single '.' between
                            String joined = base;
                            if (!rel.isEmpty()) {
                                if (!joined.endsWith(".")) joined = joined + ".";
                                joined = joined + rel;
                            }
                            // clone FieldMapping to avoid mutating original
                            MetadataDefinition.FieldMapping fm2 = new MetadataDefinition.FieldMapping();
                            fm2.column = fm.column;
                            fm2.jsonPath = joined;
                            fm2.type = fm.type;
                            fm2.nullable = fm.nullable;
                            fm2.defaultValue = fm.defaultValue;
                            fm2.index = fm.index;
                            fm2.order = fm.order;
                            fm2.remove = fm.remove;
                            fm2.exportToPlain = fm.exportToPlain;
                            fm2.plainColumn = fm.plainColumn;
                            fm2.exportDest = fm.exportDest;
                            fm2.sensitive = fm.sensitive;
                            fm2.piiMask = fm.piiMask;
                            fm2.klass = fm.klass;
                            fm2.arrayIndex = fm.arrayIndex;
                            merged.put(fm.column, fm2);
                            continue;
                        }
                    }
                    merged.put(fm.column, fm);
                }
            }
            return merged;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    // used by admin APIs / tests to evict cache when metadata changes
    public void evict(String classOrEntityType) {
        resolvedCache.invalidate(classOrEntityType);
    }

    public void evictAll() { resolvedCache.invalidateAll(); }

    public MetadataDefinition resolveForClass(String classOrEntityType) {
        try {
            Optional<vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef> dbDef = repo.findLatestEnabledByEntityType(classOrEntityType);
            MetadataDefinition md = null;
            if (dbDef.isPresent()) {
                md = mapper.readValue(dbDef.get().getJsonDefinition(), MetadataDefinition.class);
            } else {
                md = fileDefs.get(classOrEntityType);
                if (md == null) {
                    md = fileDefs.values().stream()
                            .filter(d -> classOrEntityType.equals(d.entityType) || classOrEntityType.equals(d._class))
                            .findFirst().orElse(null);
                }
            }
            return md;
        } catch (Exception e) {
            return null;
        }
    }
}
