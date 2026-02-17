package vn.com.fecredit.flowable.exposer.service;

import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.List;

/**
 * Typed wrapper around MetadataResolver to provide safe lookup helpers for
 * the annotator and other consumers.
 */
@Component
public class MetadataLookup {

    private final MetadataResolver resolver;

    public MetadataLookup(MetadataResolver resolver) {
        this.resolver = resolver;
    }

    public MetadataDefinition resolve(String className) {
        if (className == null || className.isBlank()) return null;
        return resolver.resolveForClass(className);
    }

    public List<MetadataDefinition.FieldDef> fieldsOf(MetadataDefinition md) {
        if (md == null) return null;
        return md.fields;
    }

    public static String primaryClassFor(MetadataDefinition.FieldDef fd) {
        if (fd == null) return null;
        if (fd.className != null && !fd.className.isBlank()) return fd.className;
        if (fd.type != null && !fd.type.isBlank()) return fd.type;
        return null;
    }

    public static String elementClassFor(MetadataDefinition.FieldDef fd) {
        if (fd == null) return null;
        if (fd.elementClass != null && !fd.elementClass.isBlank()) return fd.elementClass;
        if (fd.elementType != null && !fd.elementType.isBlank()) return fd.elementType;
        if ((fd.isArray != null && fd.isArray) || (fd.isList != null && fd.isList)) {
            if (fd.type != null && !fd.type.isBlank()) return fd.type;
        }
        return null;
    }

    public static String safeName(MetadataDefinition.FieldDef fd) {
        if (fd == null) return null;
        return fd.name;
    }
}
