package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef;
import vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Thin façade that caches resolved metadata and delegates heavy work to MetadataResolveEngine.
 *
 * <p>This component provides a centralized, cached interface for resolving metadata definitions
 * from multiple sources (file-backed canonical definitions and DB-backed runtime overrides).
 * It maintains two resolution strategies:
 * <ul>
 *   <li><strong>mappingsFor()</strong>: Legacy API returning column -> jsonPath mappings</li>
 *   <li><strong>mappingsMetadataFor()</strong>: New API returning full FieldMapping objects</li>
 *   <li><strong>resolveForClass()</strong>: Backwards-compatible helper returning entire MetadataDefinition</li>
 * </ul>
 *
 * <p>All resolved metadata is cached in a Caffeine cache (1024 entries, 10-minute TTL) to avoid
 * hot-loop DB queries during tight loops in CaseDataWorker. Cache can be explicitly
 * evicted on admin metadata updates.
 *
 * <p>Resolution precedence: DB override (latest enabled) > resource file > fallback candidates.
 * Diagnostic messages from {@link MetadataResolveEngine} are captured and logged.
 */
@Component
public class MetadataResolver {

    private static final Logger log = LoggerFactory.getLogger(MetadataResolver.class);

    /** Jackson ObjectMapper for parsing JSON metadata definitions */
    private final ObjectMapper mapper = new ObjectMapper();

    /** Repository for querying DB-backed metadata overrides */
    private final SysExposeClassDefRepository repo;

    /** Caffeine cache for resolved metadata: classOrEntityType -> (columnName -> FieldMapping) */
    private final Cache<String, Map<String, MetadataDefinition.FieldMapping>> resolvedCache;

    /** Loader for file-backed canonical metadata definitions */
    private final MetadataResourceLoader resourceLoader;

    /**
     * Constructs a new MetadataResolver with injected dependencies.
     *
     * <p>Initializes the metadata cache with a 1024-entry maximum size and 10-minute
     * expiration policy to balance memory usage against cache hit rates in production.
     *
     * @param repo {@link SysExposeClassDefRepository} for DB-backed metadata queries
     * @param resourceLoader {@link MetadataResourceLoader} for file-backed canonical definitions
     */
    @Autowired
    public MetadataResolver(SysExposeClassDefRepository repo, MetadataResourceLoader resourceLoader) {
        this.repo = repo;
        this.resourceLoader = resourceLoader;
        this.resolvedCache = Caffeine.newBuilder()
                .maximumSize(1024)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }

    /**
     * Returns a legacy mapping of column names to JsonPath expressions for the given class or entity type.
     *
     * <p>This method is provided for backwards compatibility with code expecting simple string
     * mappings. For new code, use {@link #mappingsMetadataFor(String)} to access full
     * FieldMapping metadata including exportToPlain flags and other extended properties.
     *
     * <p>The result is cached and normalized in insertion order (LinkedHashMap).
     *
     * @param classOrEntityType The class name (e.g., "Order") or entity type (e.g., "order")
     *        to resolve metadata for. Lookup is case-insensitive.
     * @return An immutable {@link LinkedHashMap} of column name to JsonPath string pairs.
     *         Returns an empty map if no metadata is found.
     * @see #mappingsMetadataFor(String)
     */
    public Map<String, String> mappingsFor(String classOrEntityType) {
        return mappingsMetadataFor(classOrEntityType).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().jsonPath, (a, b) -> b, LinkedHashMap::new));
    }

    /**
     * Returns full metadata mappings with FieldMapping objects for the given class or entity type.
     *
     * <p>This is the recommended API for accessing metadata. Each FieldMapping object includes:
     * <ul>
     *   <li>{@code jsonPath} - The JsonPath expression to extract data from case blob</li>
     *   <li>{@code exportToPlain} - Flag indicating whether to export to a normalized plain table</li>
     *   <li>{@code plainColumn} - Target column name in the plain table (if exportToPlain=true)</li>
     *   <li>Provenance information and diagnostic details</li>
     * </ul>
     *
     * <p>Results are cached and will be fetched from {@link #resolveAndFlatten(String)} on cache miss.
     * The resolution applies metadata inheritance and mixin rules as defined in the
     * {@link MetadataResolveEngine}.
     *
     * @param classOrEntityType The class name (e.g., "Order") or entity type (e.g., "order")
     *        to resolve metadata for. Lookup is case-insensitive.
     * @return A {@link Map} of column name to {@link MetadataDefinition.FieldMapping} objects.
     *         Returns an empty map if resolution fails or no metadata is found.
     * @see MetadataDefinition.FieldMapping
     * @see MetadataResolveEngine
     */
    public Map<String, MetadataDefinition.FieldMapping> mappingsMetadataFor(String classOrEntityType) {
        return resolvedCache.get(classOrEntityType, k -> resolveAndFlatten(classOrEntityType));
    }

    /**
     * Internal helper that performs the actual metadata resolution and flattening logic.
     *
     * <p>This method:
     * <ol>
     *   <li>Delegates to {@link MetadataResolveEngine} for complex inheritance/mixin resolution</li>
     *   <li>Captures and logs any diagnostic messages (type conflicts, cycles, etc.)</li>
     *   <li>Returns the merged and flattened field mappings</li>
     * </ol>
     *
     * <p>Exceptions during resolution are caught and result in an empty map being returned.
     * Diagnostic messages are recorded in the internal {@link #diagnostics} store.
     *
     * @param classOrEntityType The class or entity type to resolve
     * @return A flattened map of column name to FieldMapping objects, or empty map on error
     * @see MetadataResolveEngine#resolveAndFlatten(String)
     * @see MetadataResolveEngine.Result
     */
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

    /**
     * Internal store for diagnostic messages generated during metadata resolution.
     *
     * <p>Records issues such as:
     * <ul>
     *   <li>Type conflicts in field definitions</li>
     *   <li>Circular parent references</li>
     *   <li>Provenance information for resolved fields</li>
     *   <li>Mixin merging decisions</li>
     * </ul>
     *
     * <p>Accessed via {@link #diagnosticsFor(String)} and logged at WARN level.
     */
    private final Map<String, List<String>> diagnostics = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Adds a diagnostic message for the given class or entity type.
     *
     * <p>Messages are stored in-memory and also logged at WARN level.
     *
     * @param className The class or entity type that generated the diagnostic
     * @param msg The diagnostic message (e.g., "Type conflict detected for field 'amount'")
     */
    private void addDiagnostic(String className, String msg) {
        diagnostics.computeIfAbsent(className, k -> new ArrayList<>()).add(msg);
        log.warn("MetadataResolver diagnostic for {}: {}", className, msg);
    }

    /**
     * Retrieves all diagnostic messages recorded for the given class or entity type.
     *
     * <p>Useful for debugging metadata resolution issues and for admin/BA UI feedback.
     *
     * @param classOrEntityType The class or entity type to query
     * @return A list of diagnostic messages (may be empty)
     */
    public List<String> diagnosticsFor(String classOrEntityType) {
        return diagnostics.getOrDefault(classOrEntityType, List.of());
    }

    /**
     * Evicts the cached metadata for the given class or entity type.
     *
     * <p>Called by admin metadata update APIs to ensure fresh resolution on next access.
     *
     * @param classOrEntityType The class or entity type to evict from cache
     */
    public void evict(String classOrEntityType) { resolvedCache.invalidate(classOrEntityType); }

    /**
     * Evicts all cached metadata entries.
     *
     * <p>Useful for bulk metadata updates or admin operations that affect multiple classes.
     */
    public void evictAll() { resolvedCache.invalidateAll(); }

    /**
     * Backwards-compatible helper for resolving an entire MetadataDefinition object.
     *
     * <p>Resolution strategy:
     * <ol>
     *   <li>Check DB for latest enabled override</li>
     *   <li>Parse JSON definition if found</li>
     *   <li>Fall back to resource-backed file lookup</li>
     *   <li>Try entity type candidates as final fallback</li>
     * </ol>
     *
     * <p>This method is used by legacy code paths that expect a complete MetadataDefinition
     * object rather than just the resolved field mappings. New code should prefer
     * {@link #mappingsMetadataFor(String)} for better caching and performance.
     *
     * @param classOrEntityType The class name (e.g., "Order") or entity type (e.g., "order")
     * @return The resolved {@link MetadataDefinition}, or null if resolution fails
     * @see #mappingsMetadataFor(String)
     */
    public MetadataDefinition resolveForClass(String classOrEntityType) {
        try {
            Optional<SysExposeClassDef> dbDef = repo.findLatestEnabledByEntityType(classOrEntityType);
            MetadataDefinition md = null;
            if (dbDef.isPresent()) {
                try {
                    String jd = dbDef.get().getJsonDefinition();
                    log.debug("resolveForClass - found DB override for {} id={} jsonDef={}", classOrEntityType, dbDef.get().getId(), jd);
                    md = mapper.readValue(jd, MetadataDefinition.class);
                } catch (Exception ex) {
                    log.warn("resolveForClass - failed to parse DB jsonDefinition for {}: {}", classOrEntityType, ex.getMessage());
                    // fall through to resource-based resolution
                }
            }
            if (md == null) {
                md = resourceLoader.getByClass(classOrEntityType).orElse(null);
                if (md == null) {
                    md = resourceLoader.findByEntityTypeOrClassCandidates(Arrays.asList(classOrEntityType)).orElse(null);
                    if (md != null) log.debug("resolveForClass - found by entityType/resource candidates for {}", classOrEntityType);
                } else {
                    log.debug("resolveForClass - found resource file for {}", classOrEntityType);
                }
            }
            return md;
        } catch (Exception e) {
            log.error("resolveForClass - unexpected error for {}: {}", classOrEntityType, e.getMessage(), e);
            return null;
        }
    }
}
