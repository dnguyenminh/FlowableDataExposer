package vn.com.fecredit.flowable.exposer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataValidationUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates metadata files on application startup using {@link MetadataValidationUtil}.
 * Validates files under metadata/classes, metadata/exposes and metadata/indices.
 * If any metadata file is invalid, the application will fail to start.
 */
@Component
public class MetadataValidationRunner implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger log = LoggerFactory.getLogger(MetadataValidationRunner.class);

    private static final String[] METADATA_GLOBS = new String[] {
            "classpath*:metadata/classes/*.json",
            "classpath*:metadata/exposes/*.json",
            "classpath*:metadata/indices/*.json"
    };

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("MetadataValidationRunner: validating metadata files under metadata/classes/, metadata/exposes/ and metadata/indices/");
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            List<String> failures = new ArrayList<>();
            for (String glob : METADATA_GLOBS) {
                Resource[] resources = resolver.getResources(glob);
                log.debug("Checking {} resources for pattern {}", resources.length, glob);
                for (Resource r : resources) {
                    String filename = r.getFilename();
                    if (filename == null) continue;
                    String resourcePath = inferResourcePath(r, glob, filename);
                    MetadataValidationUtil.ValidationResult res = MetadataValidationUtil.validate(resourcePath);
                    if (!res.isValid()) {
                        String msg = resourcePath + " -> " + res.toString();
                        failures.add(msg);
                        log.error("Metadata validation failed for {}: {}", resourcePath, res.getErrors());
                    } else if (!res.getWarnings().isEmpty()) {
                        log.warn("Metadata validation warnings for {}: {}", resourcePath, res.getWarnings());
                    } else {
                        log.info("Metadata validated: {}", resourcePath);
                    }
                }
            }

            if (!failures.isEmpty()) {
                String all = String.join("; ", failures);
                throw new IllegalStateException("Metadata validation failed: " + all);
            }
        } catch (Exception ex) {
            log.error("Failed to validate metadata files: {}", ex.getMessage(), ex);
            throw new IllegalStateException("Failed to validate metadata files", ex);
        }
    }

    private String inferResourcePath(Resource r, String glob, String filename) {
        // Prefer using the classpath relative path under metadata/ if possible
        try {
            String path = r.getURI().getPath();
            int idx = path.indexOf("/metadata/");
            if (idx >= 0) return path.substring(idx + 1); // drop leading slash
        } catch (Exception ignored) { }
        // fallback to simple mapping based on glob
        if (glob.contains("classes")) return "metadata/classes/" + filename;
        if (glob.contains("exposes")) return "metadata/exposes/" + filename;
        if (glob.contains("indices")) return "metadata/indices/" + filename;
        return "metadata/" + filename;
    }
}
