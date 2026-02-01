package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

/**
 * Resolves metadata definitions for entity types and class names.
 *
 * Responsibilities:
 * - load canonical metadata files from classpath
 * - prefer DB-backed overrides when present
 * - resolve inheritance chains and produce merged field mappings
 *
 * The resolver is cached (Caffeine) for fast reads in hot paths.
 */
@Component
public class MetadataResolver {

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
            Resource[] classResources = resolver.getResources("classpath:metadata/classes/*.json");
            for (Resource r : classResources) {
                parseAndRegisterResource(r);
            }
        } catch (Exception e) {
            // non-fatal for runtime; tests will catch missing files
        }
    }

    /**
     * Parse one resource and register its MetadataDefinition (no-throw).
     */
    private void parseAndRegisterResource(Resource r) {
        try (InputStream is = r.getInputStream()) {
            String txt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            MetadataDefinition def = mapper.readValue(txt, MetadataDefinition.class);
            if (def._class == null) return;
            fileDefs.put(def._class, def);
        } catch (Exception ex) {
            // ignore malformed/partial files and continue
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

    /**
     * Resolve and flatten metadata for the given class or entity type.
     *
     * This orchestrator delegates to small helpers for DB/file lookup,
     * inheritance-chain construction and mapping merge (each helper ≤20 lines).
     */
    private Map<String, MetadataDefinition.FieldMapping> resolveAndFlatten(String classOrEntityType) {
        try {
            MetadataDefinition md = findDefinitionFromDbOrFiles(classOrEntityType);
            if (md == null) return Collections.emptyMap();
            List<MetadataDefinition> chain = buildInheritanceChain(md);
            return mergeFieldMappings(chain);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * Find a MetadataDefinition from DB overrides or file-backed canonical defs.
     */
    private MetadataDefinition findDefinitionFromDbOrFiles(String classOrEntityType) throws Exception {
        Optional<vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef> dbDef = repo.findLatestEnabledByEntityType(classOrEntityType);
        if (dbDef.isPresent()) return mapper.readValue(dbDef.get().getJsonDefinition(), MetadataDefinition.class);
        MetadataDefinition md = fileDefs.get(classOrEntityType);
        if (md != null) return md;
        return fileDefs.values().stream()
                .filter(d -> classOrEntityType.equals(d.entityType) || classOrEntityType.equals(d._class))
                .findFirst().orElse(null);
    }

    /**
     * Build inheritance chain (parent -> child order) honoring DB overrides.
     */
    private List<MetadataDefinition> buildInheritanceChain(MetadataDefinition md) throws Exception {
        List<MetadataDefinition> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        MetadataDefinition cur = md;
        while (cur != null && cur._class != null && !seen.contains(cur._class)) {
            chain.add(cur);
            seen.add(cur._class);
            if (cur.parent != null) {
                Optional<vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef> pdb = repo.findByClassNameOrderByVersionDesc(cur.parent).stream().findFirst();
                if (pdb.isPresent()) {
                    try { cur = mapper.readValue(pdb.get().getJsonDefinition(), MetadataDefinition.class); }
                    catch (Exception ex) { cur = fileDefs.get(cur.parent); }
                } else { cur = fileDefs.get(cur.parent); }
            } else {
                cur = null;
            }
        }
        Collections.reverse(chain);
        return chain;
    }

    /**
     * Merge the chain of MetadataDefinition into a column->FieldMapping map.
     */
    private Map<String, MetadataDefinition.FieldMapping> mergeFieldMappings(List<MetadataDefinition> chain) {
        Map<String, MetadataDefinition.FieldMapping> merged = new LinkedHashMap<>();
        for (MetadataDefinition def : chain) {
            if (def.mappings == null) continue;
            for (MetadataDefinition.FieldMapping fm : def.mappings) {
                if (Boolean.TRUE.equals(fm.remove)) { merged.remove(fm.column); continue; }
                merged.put(fm.column, fm);
            }
        }
        return merged;
    }

    // used by admin APIs / tests to evict cache when metadata changes
    public void evict(String classOrEntityType) {
        resolvedCache.invalidate(classOrEntityType);
    }

    public void evictAll() { resolvedCache.invalidateAll(); }
}
