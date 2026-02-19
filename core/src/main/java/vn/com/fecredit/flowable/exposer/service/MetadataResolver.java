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

    @Autowired
    public MetadataResolver(SysExposeClassDefRepository repo, MetadataResourceLoader resourceLoader) {
        this.repo = repo;
        this.resourceLoader = resourceLoader;
        this.resolvedCache = Caffeine.newBuilder()
                .maximumSize(1024)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }

    public Map<String, String> mappingsFor(String classOrEntityType) {
        return mappingsMetadataFor(classOrEntityType).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().jsonPath, (a, b) -> b, LinkedHashMap::new));
    }

    public Map<String, MetadataDefinition.FieldMapping> mappingsMetadataFor(String classOrEntityType) {
        log.debug("mappingsMetadataFor - resolving for {}", classOrEntityType);
        String original = classOrEntityType == null ? null : classOrEntityType.trim();
        String cacheKey = original == null ? "" : original.toLowerCase(Locale.ROOT);

        System.out.println("DEBUG[MetadataResolver]: mappingsMetadataFor called original='" + original + "' cacheKey='" + cacheKey + "'");

        // 1) Prefer explicit file-backed expose mappings (fast lookup).
        try {
            // First try direct class file lookup - test scaffolds and some resources rely on getByClass being present
            var classOpt = resourceLoader.getByClass(original);
            System.out.println("DEBUG[MetadataResolver]: fastLookup.getByClass.present=" + classOpt.isPresent());
            if (classOpt.isPresent()) {
                MetadataDefinition md = classOpt.get();
                System.out.println("DEBUG[MetadataResolver]: fastLookup.md._class=" + md._class + " entityType=" + md.entityType + " mappingsCount=" + (md.mappings==null?0:md.mappings.size()));
                if (md.mappings != null && !md.mappings.isEmpty()) {
                    Map<String, MetadataDefinition.FieldMapping> map = new LinkedHashMap<>();
                    for (MetadataDefinition.FieldMapping fm : md.mappings) {
                        System.out.println("DEBUG[MetadataResolver]: fastLookup.fm jsonPath=" + fm.jsonPath + " plainColumn=" + fm.plainColumn + " column=" + fm.column + " exportToPlain=" + fm.exportToPlain);
                        boolean exportable = Boolean.TRUE.equals(fm.exportToPlain)
                                || (fm.plainColumn != null && !fm.plainColumn.isBlank())
                                || (fm.exportDest != null && fm.exportDest.contains("plain"))
                                || (fm.column != null && !fm.column.isBlank());
                        if (!exportable) continue;
                        String key = (fm.plainColumn != null && !fm.plainColumn.isBlank()) ? fm.plainColumn
                                : (fm.column != null && !fm.column.isBlank() ? fm.column : fm.jsonPath);
                        MetadataDefinition.FieldMapping copy = MetadataResolveHelpers.cloneMappingWithProvenance(fm, md, "file");
                        if ((copy.column == null || copy.column.isBlank()) && key != null && !key.equals(copy.jsonPath)) copy.column = key;
                        if (copy.plainColumn != null && !copy.plainColumn.isBlank()) copy.exportToPlain = true;
                        map.put(key, copy);
                    }
                    if (!map.isEmpty()) {
                        System.out.println("DEBUG[MetadataResolver]: returning resource-backed keys=" + map.keySet());
                        log.debug("mappingsMetadataFor - resource exposes provided keys for {}: {}", classOrEntityType, map.keySet());
                        return map;
                    }
                }
            }

            // Next try entityType/class candidate lookup
            var opt = resourceLoader.findByEntityTypeOrClassCandidates(Arrays.asList(original, cacheKey));
            System.out.println("DEBUG[MetadataResolver]: fastLookup.present=" + opt.isPresent());
            if (opt.isPresent()) {
                MetadataDefinition md = opt.get();
                System.out.println("DEBUG[MetadataResolver]: fastLookup.md._class=" + md._class + " entityType=" + md.entityType + " mappingsCount=" + (md.mappings==null?0:md.mappings.size()));
                // If the matched resource is an entityType match but there's an explicit class file for the requested class,
                // prefer scanning the class file so that class-specific exposes override entity-type exposes.
                boolean matchedByEntityTypeOnly = md._class != null && !md._class.equalsIgnoreCase(original) && md.entityType != null && md.entityType.equalsIgnoreCase(original);
                if (matchedByEntityTypeOnly && resourceLoader.getByClass(original).isPresent()) {
                    System.out.println("DEBUG[MetadataResolver]: skipping entity-type fastLookup in favor of explicit class file for " + original);
                } else if (md.mappings != null && !md.mappings.isEmpty()) {
                    Map<String, MetadataDefinition.FieldMapping> map = new LinkedHashMap<>();
                    for (MetadataDefinition.FieldMapping fm : md.mappings) {
                        System.out.println("DEBUG[MetadataResolver]: fastLookup.fm jsonPath=" + fm.jsonPath + " plainColumn=" + fm.plainColumn + " column=" + fm.column + " exportToPlain=" + fm.exportToPlain);
                        boolean exportable = Boolean.TRUE.equals(fm.exportToPlain)
                                || (fm.plainColumn != null && !fm.plainColumn.isBlank())
                                || (fm.exportDest != null && fm.exportDest.contains("plain"))
                                || (fm.column != null && !fm.column.isBlank());
                        if (!exportable) continue;
                        String key = (fm.plainColumn != null && !fm.plainColumn.isBlank()) ? fm.plainColumn
                                : (fm.column != null && !fm.column.isBlank() ? fm.column : fm.jsonPath);
                        MetadataDefinition.FieldMapping copy = MetadataResolveHelpers.cloneMappingWithProvenance(fm, md, "file");
                        if ((copy.column == null || copy.column.isBlank()) && key != null && !key.equals(copy.jsonPath)) copy.column = key;
                        if (copy.plainColumn != null && !copy.plainColumn.isBlank()) copy.exportToPlain = true;
                        map.put(key, copy);
                    }
                    if (!map.isEmpty()) {
                        System.out.println("DEBUG[MetadataResolver]: returning resource-backed keys=" + map.keySet());
                        log.debug("mappingsMetadataFor - resource exposes provided keys for {}: {}", classOrEntityType, map.keySet());
                        return map;
                    }
                }
            }

            // If fast lookup returned a canonical class without exposes, scan all loaded file defs
            for (MetadataDefinition fdef : resourceLoader.all()) {
                System.out.println("DEBUG[MetadataResolver]: scanning fileDef class=" + (fdef==null?null:fdef._class) + " entityType=" + (fdef==null?null:fdef.entityType) + " mappingsCount=" + (fdef==null||fdef.mappings==null?0:fdef.mappings.size()));
                if (fdef == null || fdef.mappings == null || fdef.mappings.isEmpty()) continue;
                boolean match = (fdef._class != null && fdef._class.equalsIgnoreCase(original))
                        || (fdef.entityType != null && fdef.entityType.equalsIgnoreCase(original));
                if (!match) continue;
                Map<String, MetadataDefinition.FieldMapping> map = new LinkedHashMap<>();
                for (MetadataDefinition.FieldMapping fm : fdef.mappings) {
                    System.out.println("DEBUG[MetadataResolver]: scanning fm jsonPath=" + fm.jsonPath + " plainColumn=" + fm.plainColumn + " column=" + fm.column + " exportToPlain=" + fm.exportToPlain);
                    boolean exportable = Boolean.TRUE.equals(fm.exportToPlain)
                            || (fm.plainColumn != null && !fm.plainColumn.isBlank())
                            || (fm.exportDest != null && fm.exportDest.contains("plain"))
                            || (fm.column != null && !fm.column.isBlank());
                    if (!exportable) continue;
                    String key = (fm.plainColumn != null && !fm.plainColumn.isBlank()) ? fm.plainColumn
                            : (fm.column != null && !fm.column.isBlank() ? fm.column : fm.jsonPath);
                    MetadataDefinition.FieldMapping copy = MetadataResolveHelpers.cloneMappingWithProvenance(fm, fdef, "file");
                    if ((copy.column == null || copy.column.isBlank()) && key != null && !key.equals(copy.jsonPath)) copy.column = key;
                    if (copy.plainColumn != null && !copy.plainColumn.isBlank()) copy.exportToPlain = true;
                    map.put(key, copy);
                }
                if (!map.isEmpty()) {
                    System.out.println("DEBUG[MetadataResolver]: returning scanned file-backed keys=" + map.keySet());
                    log.debug("mappingsMetadataFor - located file-backed expose mappings for {}: {}", classOrEntityType, map.keySet());
                    return map;
                }
            }
        } catch (Exception e) {
            log.debug("mappingsMetadataFor - resource lookup failed for {}: {}", classOrEntityType, e.getMessage());
        }

        // 2) Fallback: use resolved flattened metadata and prefer exportable/plain mappings
        Map<String, MetadataDefinition.FieldMapping> resolved = resolvedCache.get(cacheKey, k -> resolveAndFlatten(original));
        System.out.println("DEBUG[MetadataResolver]: resolvedCache returned size=" + (resolved==null?0:resolved.size()));
        try {
            if (resolved != null && !resolved.isEmpty()) {
                Map<String, MetadataDefinition.FieldMapping> exportables = resolved.entrySet().stream()
                        .filter(e -> {
                            MetadataDefinition.FieldMapping fm = e.getValue();
                            return Boolean.TRUE.equals(fm.exportToPlain) || (fm.plainColumn != null && !fm.plainColumn.isBlank()) || (fm.exportDest != null && fm.exportDest.contains("plain"));
                        })
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new));
                System.out.println("DEBUG[MetadataResolver]: exportables from resolved size=" + (exportables==null?0:exportables.size()));
                if (!exportables.isEmpty()) {
                    // ensure exportToPlain flag is set when plainColumn is provided so callers can rely on it
                    exportables.values().forEach(fm -> { if ((fm.exportToPlain == null || !fm.exportToPlain) && fm.plainColumn != null && !fm.plainColumn.isBlank()) fm.exportToPlain = true; });
                    System.out.println("DEBUG[MetadataResolver]: returning resolved exportable keys=" + exportables.keySet());
                    log.debug("mappingsMetadataFor - resolved exportable keys for {}: {}", classOrEntityType, exportables.keySet());
                    return exportables;
                }
                log.debug("mappingsMetadataFor - resolved keys for {}: {}", classOrEntityType, resolved.keySet());
            } else {
                log.debug("mappingsMetadataFor - no mappings resolved for {}", classOrEntityType);
            }
        } catch (Exception ignored) {}

        // 3) Try DB override parsing for explicit expose mappings
        try {
            Optional<SysExposeClassDef> dbDef = repo.findLatestEnabledByEntityType(classOrEntityType);
            System.out.println("DEBUG[MetadataResolver]: db override present=" + (dbDef.isPresent()));
            if (dbDef.isPresent()) {
                String jd = dbDef.get().getJsonDefinition();
                try {
                    MetadataDefinition md = mapper.readValue(jd, MetadataDefinition.class);
                    System.out.println("DEBUG[MetadataResolver]: parsed db md._class=" + (md==null?null:md._class) + " mappingsCount=" + (md==null||md.mappings==null?0:md.mappings.size()));
                    if (md != null && md.mappings != null && !md.mappings.isEmpty()) {
                        Map<String, MetadataDefinition.FieldMapping> map = new LinkedHashMap<>();
                        for (MetadataDefinition.FieldMapping fm : md.mappings) {
                            System.out.println("DEBUG[MetadataResolver]: db fm jsonPath=" + fm.jsonPath + " plainColumn=" + fm.plainColumn + " column=" + fm.column + " exportToPlain=" + fm.exportToPlain);
                            boolean exportable = Boolean.TRUE.equals(fm.exportToPlain) || (fm.plainColumn != null && !fm.plainColumn.isBlank()) || (fm.exportDest != null && fm.exportDest.contains("plain")) || (fm.column != null && !fm.column.isBlank());
                            if (!exportable) continue;
                            String key = (fm.plainColumn != null && !fm.plainColumn.isBlank()) ? fm.plainColumn : (fm.column != null && !fm.column.isBlank() ? fm.column : fm.jsonPath);
                            MetadataDefinition.FieldMapping copy = MetadataResolveHelpers.cloneMappingWithProvenance(fm, md, "db");
                            if ((copy.column == null || copy.column.isBlank()) && key != null && !key.equals(copy.jsonPath)) copy.column = key;
                            if (copy.plainColumn != null && !copy.plainColumn.isBlank()) copy.exportToPlain = true;
                            map.put(key, copy);
                        }
                        if (!map.isEmpty()) {
                            System.out.println("DEBUG[MetadataResolver]: returning db-backed keys=" + map.keySet());
                            log.debug("mappingsMetadataFor - db fallback resolved keys for {}: {}", classOrEntityType, map.keySet());
                            return map;
                        }
                    }
                } catch (Exception ex) {
                    log.debug("mappingsMetadataFor - failed to parse DB jsonDefinition for {}: {}", classOrEntityType, ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.debug("mappingsMetadataFor - db lookup failed for {}: {}", classOrEntityType, ex.getMessage());
        }

        return Collections.emptyMap();
    }

    private Map<String, MetadataDefinition.FieldMapping> resolveAndFlatten(String classOrEntityType) {
        try {
            MetadataResolveEngine engine = new MetadataResolveEngine(repo, resourceLoader, mapper);
            MetadataResolveEngine.Result r = engine.resolveAndFlatten(classOrEntityType);
            r.diagnostics.forEach((k, v) -> v.forEach(msg -> addDiagnostic(k, msg)));
            return r.merged;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private final Map<String, List<String>> diagnostics = new java.util.concurrent.ConcurrentHashMap<>();

    private void addDiagnostic(String className, String msg) {
        diagnostics.computeIfAbsent(className, k -> new ArrayList<>()).add(msg);
        log.warn("MetadataResolver diagnostic for {}: {}", className, msg);
    }

    public List<String> diagnosticsFor(String classOrEntityType) {
        return diagnostics.getOrDefault(classOrEntityType, List.of());
    }

    public void evict(String classOrEntityType) { resolvedCache.invalidate(classOrEntityType); }

    public void evictAll() { resolvedCache.invalidateAll(); }

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
