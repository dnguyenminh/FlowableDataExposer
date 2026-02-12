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

/**
 * Resolves metadata definitions for entity classes and entityTypes.
 *
 * <p>Runtime strategy:
 * - prefer the latest enabled DB-backed definition (for admin overrides)
 * - otherwise fall back to file-backed canonical definitions in
 *   <code>src/main/resources/metadata/</code>
 * - support inheritance (parent chain) and nested-class resolution
 *   used by the indexer and {@code CaseDataWorker}.</p>
 *
 * Responsibilities:
 * - provide merged/flattened field mappings (jsonPath + plainColumn)
 * - cache resolved mappings (Caffeine) and expose eviction methods
 * - remain backwards-compatible with legacy column->jsonPath mappings
 */
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
                    // ignore canonical files that have been migrated out of core (avoid duplicate defs on classpath)
                    if (Boolean.TRUE.equals(def.deprecated) || (def.migratedToModule != null && !def.migratedToModule.isBlank())) {
                        log.debug("Skipping migrated/ deprecated metadata file: {} -> class={} migratedTo={}", r.getFilename(), def._class, def.migratedToModule);
                        continue;
                    }
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
            MetadataDefinition md = null;

            // Build candidate keys to attempt resolution. This allows aliases such as
            // process definition keys (e.g. "orderProcess") to resolve to the canonical
            // metadata class (e.g. "Order").
            List<String> candidates = new ArrayList<>();
            if (classOrEntityType != null) candidates.add(classOrEntityType);
            String lower = classOrEntityType == null ? "" : classOrEntityType.toLowerCase(Locale.ROOT);
            if (lower.endsWith("process")) {
                String base = classOrEntityType.substring(0, classOrEntityType.length() - "process".length());
                if (!base.isBlank()) {
                    candidates.add(base);
                    candidates.add(Character.toUpperCase(base.charAt(0)) + base.substring(1));
                }
            }

            // try DB-backed defs for any candidate
            for (String cand : candidates) {
                try {
                    Optional<vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef> dbDef = repo.findLatestEnabledByEntityType(cand);
                    if (dbDef.isPresent()) {
                        md = mapper.readValue(dbDef.get().getJsonDefinition(), MetadataDefinition.class);
                        break;
                    }
                } catch (Exception ex) {
                    // ignore and continue
                }
            }

            // fallback: try file-backed defs by exact _class key (case-sensitive), then case-insensitive search
            if (md == null) {
                for (String cand : candidates) {
                    md = fileDefs.get(cand);
                    if (md == null) {
                        // try case-insensitive match on _class key
                        md = fileDefs.entrySet().stream()
                                .filter(e -> e.getKey().equalsIgnoreCase(cand))
                                .map(Map.Entry::getValue)
                                .findFirst().orElse(null);
                    }
                    if (md != null) break;
                }
            }

            // final fallback: search fileDefs by entityType/_class equality against candidates (case-insensitive)
            if (md == null) {
                md = fileDefs.values().stream()
                        .filter(d -> candidates.stream().anyMatch(c -> (d.entityType != null && c.equalsIgnoreCase(d.entityType)) || (d._class != null && c.equalsIgnoreCase(d._class))))
                        .findFirst().orElse(null);
            }

            if (md == null) return Collections.emptyMap();

            List<MetadataDefinition> chain = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            MetadataDefinition cur = md;
            while (cur != null && cur._class != null && !seen.contains(cur._class)) {
                chain.add(cur);
                seen.add(cur._class);
                if (cur.parent != null) {
                    // detect parent cycle
                    if (seen.contains(cur.parent)) {
                        addDiagnostic(md._class, "circular parent reference detected: " + cur.parent + " (referenced by " + cur._class + ")");
                        break;
                    }
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
                // first: apply any mixins declared on this definition (mixins may override parent values)
                if (def.mixins != null) {
                    for (String mixinName : def.mixins) {
                        if (mixinName == null || mixinName.isBlank()) continue;
                        try {
                            if (seen.contains(mixinName)) {
                                addDiagnostic(md._class, "circular mixin reference detected: " + mixinName + " (referenced by " + def._class + ")");
                                continue;
                            }
                            MetadataDefinition mixin = fileDefs.get(mixinName);
                            if (mixin == null) {
                                Optional<vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef> mdb = repo.findByClassNameOrderByVersionDesc(mixinName).stream().findFirst();
                                if (mdb.isPresent()) mixin = mapper.readValue(mdb.get().getJsonDefinition(), MetadataDefinition.class);
                            }
                            if (mixin == null || mixin.mappings == null) continue;
                            if (mixin._class != null && !seen.contains(mixin._class)) {
                                for (MetadataDefinition.FieldMapping fm : mixin.mappings) {
                                    if (fm.remove != null && fm.remove) {
                                        merged.remove(fm.column);
                                        continue;
                                    }
                                    // set provenance for mixin-sourced mapping
                                    MetadataDefinition.FieldMapping fmCopy = cloneMappingWithProvenance(fm, mixin, "file");
                                    // detect type conflict on plainColumn
                                    checkTypeConflict(md._class, merged, fmCopy);
                                    // reuse nested-class resolution logic from below (simplified path join)
                                    if (fmCopy.klass != null && !fmCopy.klass.isBlank()) {
                                        MetadataDefinition nested = fileDefs.get(fmCopy.klass);
                                        if (nested != null && nested.jsonPath != null && !nested.jsonPath.isBlank()) {
                                            String base = nested.jsonPath.trim();
                                            String rel = fmCopy.jsonPath == null ? "" : fmCopy.jsonPath.trim();
                                            // strip leading '$' and optional '.' from relative path
                                            if (rel.startsWith("$")) rel = rel.substring(1);
                                            if (rel.startsWith(".")) rel = rel.substring(1);
                                            // preserve explicit index or map access in rel (items(0) or items[0] or [key])
                                            if (fmCopy.arrayIndex != null && !rel.contains("(") && !rel.contains("[")) {
                                                if (!base.endsWith("]")) base = base + "[" + fmCopy.arrayIndex + "]";
                                            }
                                            // join without inserting extra dot when rel starts with '(' or '['
                                            String joined = base;
                                            if (!rel.isEmpty()) {
                                                if (!joined.endsWith(".") && !rel.startsWith("(") && !rel.startsWith("[") && !rel.startsWith("."))
                                                    joined = joined + ".";
                                                joined = joined + rel;
                                            }
                                            fmCopy.jsonPath = joined;
                                        }
                                    }
                                    merged.put(fmCopy.column, fmCopy);
                                }
                                seen.add(mixin._class);
                            }
                        } catch (Exception ex) {
                            addDiagnostic(md._class, "mixin resolution failed for " + mixinName + ": " + ex.getMessage());
                        }
                    }
                }

                if (def.mappings == null) continue;
                for (MetadataDefinition.FieldMapping fm : def.mappings) {
                    if (fm.remove != null && fm.remove) {
                        merged.remove(fm.column);
                        continue;
                    }

                    MetadataDefinition.FieldMapping fmToApply = cloneMappingWithProvenance(fm, def, "file");

                    // If mapping references a nested class (klass), resolve that class's jsonPath and adjust the mapping jsonPath accordingly
                    if (fmToApply.klass != null && !fmToApply.klass.isBlank()) {
                        MetadataDefinition nested = fileDefs.get(fmToApply.klass);
                        if (nested == null) {
                            // try to find among chain
                            nested = chain.stream().filter(d -> fmToApply.klass.equals(d._class)).findFirst().orElse(null);
                        }
                        if (nested != null && nested.jsonPath != null && !nested.jsonPath.isBlank()) {
                            // combine nested.jsonPath and fm.jsonPath (which is relative to the class)
                            String base = nested.jsonPath.trim();
                            String rel = fmToApply.jsonPath == null ? "" : fmToApply.jsonPath.trim();
                            // strip leading '$' and optional '.' from relative path
                            if (rel.startsWith("$")) rel = rel.substring(1);
                            if (rel.startsWith(".")) rel = rel.substring(1);
                            // preserve explicit index or map access in rel; only apply legacy arrayIndex when rel lacks index notation
                            if (fmToApply.arrayIndex != null && !rel.contains("(") && !rel.contains("[")) {
                                if (!base.endsWith("]")) base = base + "[" + fmToApply.arrayIndex + "]";
                            }
                            // join without inserting extra dot when rel starts with '(' or '['
                            String joined = base;
                            if (!rel.isEmpty()) {
                                if (!joined.endsWith(".") && !rel.startsWith("(") && !rel.startsWith("[") && !rel.startsWith("."))
                                    joined = joined + ".";
                                joined = joined + rel;
                            }
                            fmToApply.jsonPath = joined;
                        }
                    }

                    checkTypeConflict(md._class, merged, fmToApply);
                    merged.put(fmToApply.column, fmToApply);
                }
            }
            return merged;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    // diagnostics & helpers
    private final Map<String, java.util.List<String>> diagnostics = new java.util.concurrent.ConcurrentHashMap<>();

    private void addDiagnostic(String className, String msg) {
        diagnostics.computeIfAbsent(className, k -> new java.util.ArrayList<>()).add(msg);
        log.warn("MetadataResolver diagnostic for {}: {}", className, msg);
    }

    private MetadataDefinition.FieldMapping cloneMappingWithProvenance(MetadataDefinition.FieldMapping fm, MetadataDefinition src, String kind) {
        MetadataDefinition.FieldMapping fm2 = new MetadataDefinition.FieldMapping();
        fm2.column = fm.column;
        fm2.jsonPath = fm.jsonPath;
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
        fm2.sourceClass = src._class;
        fm2.sourceKind = kind;
        fm2.sourceModule = src.migratedToModule == null ? "core" : src.migratedToModule;
        fm2.sourceLocation = src._class + "(file)";
        return fm2;
    }

    private void checkTypeConflict(String targetClass, Map<String, MetadataDefinition.FieldMapping> merged, MetadataDefinition.FieldMapping candidate) {
        if (candidate.plainColumn == null) return;
        for (MetadataDefinition.FieldMapping existing : merged.values()) {
            if (candidate.plainColumn.equals(existing.plainColumn) && existing.type != null && candidate.type != null && !existing.type.equals(candidate.type)) {
                addDiagnostic(targetClass, String.format("type conflict for plainColumn '%s' (existing=%s@%s, candidate=%s@%s)", candidate.plainColumn, existing.type, existing.sourceClass, candidate.type, candidate.sourceClass));
            }
        }
    }

    public java.util.List<String> diagnosticsFor(String classOrEntityType) {
        return diagnostics.getOrDefault(classOrEntityType, java.util.List.of());
    }

    // used by admin APIs / tests to evict cache when metadata changes
    public void evict(String classOrEntityType) {
        resolvedCache.invalidate(classOrEntityType);
    }

    public void evictAll() { resolvedCache.invalidateAll(); }

    // Backwards-compatible resolver helper expected by other modules (web)
    // Provide the same helper signatures that older/other-module code may call.

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

    private MetadataDefinition findDefinitionFromDbOrFiles(String classOrEntityType) throws Exception {
        // reuse existing resolveForClass behaviour
        return resolveForClass(classOrEntityType);
    }

    private List<MetadataDefinition> buildInheritanceChain(MetadataDefinition md) throws Exception {
        List<MetadataDefinition> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        MetadataDefinition cur = md;
        while (cur != null && cur._class != null && !seen.contains(cur._class)) {
            chain.add(cur);
            seen.add(cur._class);
            if (cur.parent != null) {
                if (seen.contains(cur.parent)) {
                    addDiagnostic(md._class, "circular parent reference detected: " + cur.parent + " (referenced by " + cur._class + ")");
                    break;
                }
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
        return chain;
    }

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
