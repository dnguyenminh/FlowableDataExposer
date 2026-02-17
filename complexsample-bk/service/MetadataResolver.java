package vn.com.fecredit.complexsample.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.fecredit.complexsample.entity.SysExposeClassDef;
import vn.com.fecredit.complexsample.repository.SysExposeClassDefRepository;
import vn.com.fecredit.complexsample.service.metadata.MetadataDefinition;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Thin façade that caches resolved metadata and delegates heavy work to MetadataResolveEngine.
 */
@Component
public class MetadataResolver {

    private static final Logger log = LoggerFactory.getLogger(MetadataResolver.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final SysExposeClassDefRepository repo;
    private final Cache<String, Map<String, MetadataDefinition.FieldMapping>> resolvedCache;
    private final MetadataResourceLoader resourceLoader;

    @Autowired
    public MetadataResolver(SysExposeClassDefRepository repo, MetadataResourceLoader resourceLoader) {
        this.repo = repo;
        this.resourceLoader = resourceLoader;
        this.resolvedCache = Caffeine.newBuilder()
                .maximumSize(1024)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }

    /** Backwards-compatible: column -> jsonPath */
    public Map<String, String> mappingsFor(String classOrEntityType) {
        return mappingsMetadataFor(classOrEntityType).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().jsonPath, (a, b) -> b, LinkedHashMap::new));
    }

    /** New API: return full FieldMapping objects */
    public Map<String, MetadataDefinition.FieldMapping> mappingsMetadataFor(String classOrEntityType) {
        return resolvedCache.get(classOrEntityType, k -> resolveAndFlatten(classOrEntityType));
    }

    private Map<String, MetadataDefinition.FieldMapping> resolveAndFlatten(String classOrEntityType) {
        try {
            MetadataResolveEngine engine = new MetadataResolveEngine(repo, resourceLoader, mapper);
            MetadataResolveEngine.Result r = engine.resolveAndFlatten(classOrEntityType);
            // copy diagnostics from engine into local diagnostics store (keeps logging behavior)
            r.diagnostics.forEach((k, v) -> v.forEach(msg -> addDiagnostic(k, msg)));
            return r.merged;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    // diagnostics store (kept for API/testing)
    private final Map<String, java.util.List<String>> diagnostics = new java.util.concurrent.ConcurrentHashMap<>();

    private void addDiagnostic(String className, String msg) {
        diagnostics.computeIfAbsent(className, k -> new java.util.ArrayList<>()).add(msg);
        log.warn("MetadataResolver diagnostic for {}: {}", className, msg);
    }

    public java.util.List<String> diagnosticsFor(String classOrEntityType) {
        return diagnostics.getOrDefault(classOrEntityType, java.util.List.of());
    }

    // used by admin APIs / tests to evict cache when metadata changes
    public void evict(String classOrEntityType) { resolvedCache.invalidate(classOrEntityType); }

    public void evictAll() { resolvedCache.invalidateAll(); }

    // Backwards-compatible helper expected by other modules (web)
    public MetadataDefinition resolveForClass(String classOrEntityType) {
        try {
            Optional<SysExposeClassDef> dbDef = repo.findLatestEnabledByEntityType(classOrEntityType);
            MetadataDefinition md = null;
            if (dbDef.isPresent()) {
                md = mapper.readValue(dbDef.get().getJsonDefinition(), MetadataDefinition.class);
            } else {
                md = resourceLoader.getByClass(classOrEntityType).orElse(null);
                if (md == null) {
                    md = resourceLoader.findByEntityTypeOrClassCandidates(Arrays.asList(classOrEntityType)).orElse(null);
                }
            }
            return md;
        } catch (Exception e) {
            return null;
        }
    }
}
