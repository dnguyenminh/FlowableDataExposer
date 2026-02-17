package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef;
import vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.*;

/**
 * Engine extracted from MetadataResolver to perform heavy resolve+flatten work.
 * This version breaks resolve steps into small helpers to satisfy file/method size rules.
 */
public final class MetadataResolveEngine {
    private final SysExposeClassDefRepository repo;
    private final MetadataResourceLoader resourceLoader;
    private final ObjectMapper mapper;

    public MetadataResolveEngine(SysExposeClassDefRepository repo, MetadataResourceLoader resourceLoader, ObjectMapper mapper) {
        this.repo = repo;
        this.resourceLoader = resourceLoader;
        this.mapper = mapper;
    }

    public static final class Result {
        public final Map<String, MetadataDefinition.FieldMapping> merged;
        public final Map<String, List<String>> diagnostics;
        public Result(Map<String, MetadataDefinition.FieldMapping> merged, Map<String, List<String>> diagnostics) {
            this.merged = merged;
            this.diagnostics = diagnostics;
        }
    }

    public Result resolveAndFlatten(String classOrEntityType) {
        Map<String, List<String>> diagnostics = new HashMap<>();
        try {
            List<String> candidates = buildCandidates(classOrEntityType);
            MetadataDefinition md = loadDefinitionForCandidates(candidates);
            if (md == null) return new Result(Collections.emptyMap(), diagnostics);

            List<MetadataDefinition> chain = buildInheritanceChain(md, diagnostics);
            Map<String, MetadataDefinition.FieldMapping> merged = new LinkedHashMap<>();

            for (MetadataDefinition def : chain) {
                applyMixins(def, merged, chain, diagnostics);
                applyMappings(def, merged, chain, diagnostics);
            }

            return new Result(merged, diagnostics);
        } catch (Exception e) {
            return new Result(Collections.emptyMap(), diagnostics);
        }
    }

    // small helpers
    private List<String> buildCandidates(String classOrEntityType) {
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
        return candidates;
    }

    private MetadataDefinition loadDefinitionForCandidates(List<String> candidates) {
        for (String cand : candidates) {
            try {
                Optional<SysExposeClassDef> dbDef = repo.findLatestEnabledByEntityType(cand);
                if (dbDef.isPresent()) return mapper.readValue(dbDef.get().getJsonDefinition(), MetadataDefinition.class);
            } catch (Exception ex) {
                // ignore and continue
            }
        }
        for (String cand : candidates) {
            Optional<MetadataDefinition> opt = resourceLoader.getByClass(cand);
            if (opt.isPresent()) return opt.get();
        }
        Optional<MetadataDefinition> found = resourceLoader.findByEntityTypeOrClassCandidates(candidates);
        return found.orElse(null);
    }

    private List<MetadataDefinition> buildInheritanceChain(MetadataDefinition md, Map<String, List<String>> diagnostics) throws Exception {
        List<MetadataDefinition> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        MetadataDefinition cur = md;
        while (cur != null && cur._class != null && !seen.contains(cur._class)) {
            chain.add(cur);
            seen.add(cur._class);
            if (cur.parent != null) {
                if (seen.contains(cur.parent)) {
                    addDiagnostic(diagnostics, md._class, "circular parent reference detected: " + cur.parent + " (referenced by " + cur._class + ")");
                    break;
                }
                Optional<SysExposeClassDef> pdb = repo.findByClassNameOrderByVersionDesc(cur.parent).stream().findFirst();
                if (pdb.isPresent()) {
                    cur = mapper.readValue(pdb.get().getJsonDefinition(), MetadataDefinition.class);
                } else {
                    cur = resourceLoader.getByClass(cur.parent).orElse(null);
                }
            } else {
                cur = null;
            }
        }
        Collections.reverse(chain);
        return chain;
    }

    private void applyMixins(MetadataDefinition def, Map<String, MetadataDefinition.FieldMapping> merged, List<MetadataDefinition> chain, Map<String, List<String>> diagnostics) {
        if (def.mixins == null) return;
        Set<String> chainClasses = new HashSet<>();
        for (MetadataDefinition d : chain) if (d._class != null) chainClasses.add(d._class);
        for (String mixinName : def.mixins) {
            if (mixinName == null || mixinName.isBlank()) continue;
            try {
                if (chainClasses.contains(mixinName)) {
                    addDiagnostic(diagnostics, def._class, "circular mixin reference detected: " + mixinName + " (referenced by " + def._class + ")");
                    continue;
                }
                MetadataDefinition mixin = resourceLoader.getByClass(mixinName).orElse(null);
                if (mixin == null) {
                    Optional<SysExposeClassDef> mdb = repo.findByClassNameOrderByVersionDesc(mixinName).stream().findFirst();
                    if (mdb.isPresent()) mixin = mapper.readValue(mdb.get().getJsonDefinition(), MetadataDefinition.class);
                }
                if (mixin == null || mixin.mappings == null) continue;
                for (MetadataDefinition.FieldMapping fm : mixin.mappings) {
                    if (fm.remove != null && fm.remove) { merged.remove(fm.column); continue; }
                    MetadataDefinition.FieldMapping fmCopy = MetadataResolveHelpers.cloneMappingWithProvenance(fm, mixin, "file");
                    MetadataResolveHelpers.checkTypeConflict(def._class, merged, fmCopy, diagnostics);
                    resolveNestedJsonPathIfNeeded(fmCopy);
                    merged.put(fmCopy.column, fmCopy);
                }
            } catch (Exception ex) {
                addDiagnostic(diagnostics, def._class, "mixin resolution failed for " + mixinName + ": " + ex.getMessage());
            }
        }
    }

    private void applyMappings(MetadataDefinition def, Map<String, MetadataDefinition.FieldMapping> merged, List<MetadataDefinition> chain, Map<String, List<String>> diagnostics) {
        if (def.mappings == null) return;
        for (MetadataDefinition.FieldMapping fm : def.mappings) {
            if (fm.remove != null && fm.remove) { merged.remove(fm.column); continue; }
            MetadataDefinition.FieldMapping fmToApply = MetadataResolveHelpers.cloneMappingWithProvenance(fm, def, "file");
            resolveNestedJsonPathWithChain(fmToApply, chain);
            MetadataResolveHelpers.checkTypeConflict(def._class, merged, fmToApply, diagnostics);
            merged.put(fmToApply.column, fmToApply);
        }
    }

    private void resolveNestedJsonPathIfNeeded(MetadataDefinition.FieldMapping fm) {
        if (fm.klass == null || fm.klass.isBlank() || fm.jsonPath == null) return;
        MetadataDefinition nested = resourceLoader.getByClass(fm.klass).orElse(null);
        if (nested != null && nested.jsonPath != null && !nested.jsonPath.isBlank()) {
            fm.jsonPath = joinNestedJsonPath(nested.jsonPath, fm.jsonPath, fm.arrayIndex);
        }
    }

    private void resolveNestedJsonPathWithChain(MetadataDefinition.FieldMapping fm, List<MetadataDefinition> chain) {
        if (fm.klass == null || fm.klass.isBlank()) return;
        MetadataDefinition nested = resourceLoader.getByClass(fm.klass).orElse(null);
        if (nested == null) nested = chain.stream().filter(d -> fm.klass.equals(d._class)).findFirst().orElse(null);
        if (nested != null && nested.jsonPath != null && !nested.jsonPath.isBlank()) {
            fm.jsonPath = joinNestedJsonPath(nested.jsonPath, fm.jsonPath, fm.arrayIndex);
        }
    }

    private String joinNestedJsonPath(String base, String rel, Integer arrayIndex) {
        String b = base.trim();
        String r = rel == null ? "" : rel.trim();
        if (r.startsWith("$")) r = r.substring(1);
        if (r.startsWith(".")) r = r.substring(1);
        if (arrayIndex != null && !r.contains("(") && !r.contains("[")) {
            if (!b.endsWith("]")) b = b + "[" + arrayIndex + "]";
        }
        String joined = b;
        if (!r.isEmpty()) {
            if (!joined.endsWith(".") && !r.startsWith("(") && !r.startsWith("[") && !r.startsWith(".")) joined = joined + ".";
            joined = joined + r;
        }
        return joined;
    }

    private void addDiagnostic(Map<String, List<String>> diagnostics, String className, String msg) {
        diagnostics.computeIfAbsent(className, k -> new ArrayList<>()).add(msg);
    }
}
