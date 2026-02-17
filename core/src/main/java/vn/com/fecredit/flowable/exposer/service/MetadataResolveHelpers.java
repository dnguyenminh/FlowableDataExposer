package vn.com.fecredit.flowable.exposer.service;


import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.List;
import java.util.Map;

public final class MetadataResolveHelpers {

    private MetadataResolveHelpers() {}

    public static MetadataDefinition.FieldMapping cloneMappingWithProvenance(MetadataDefinition.FieldMapping fm, MetadataDefinition src, String kind) {
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

    public static void checkTypeConflict(String targetClass, Map<String, MetadataDefinition.FieldMapping> merged, MetadataDefinition.FieldMapping candidate, Map<String, List<String>> diagnostics) {
        if (candidate.plainColumn == null) return;
        for (MetadataDefinition.FieldMapping existing : merged.values()) {
            if (candidate.plainColumn.equals(existing.plainColumn) && existing.type != null && candidate.type != null && !existing.type.equals(candidate.type)) {
                diagnostics.computeIfAbsent(targetClass, k -> new java.util.ArrayList<>()).add(
                        String.format("type conflict for plainColumn '%s' (existing=%s@%s, candidate=%s@%s)", candidate.plainColumn, existing.type, existing.sourceClass, candidate.type, candidate.sourceClass)
                );
            }
        }
    }
}
