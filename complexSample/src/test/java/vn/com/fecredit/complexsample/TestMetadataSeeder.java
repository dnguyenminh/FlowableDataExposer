package vn.com.fecredit.complexsample;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import vn.com.fecredit.flowable.exposer.service.MetadataResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Component
public class TestMetadataSeeder implements ApplicationListener<ContextRefreshedEvent> {
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MetadataResolver metadataResolver;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        try {
            // ensure table schema matches SysExposeClassDef entity (json_definition column)
            jdbc.execute("CREATE TABLE IF NOT EXISTS sys_expose_class_def (id BIGINT AUTO_INCREMENT PRIMARY KEY, class_name VARCHAR(255), entity_type VARCHAR(255), json_definition CLOB, enabled BOOLEAN DEFAULT TRUE, version INTEGER DEFAULT 1)");
        } catch (Exception ignored) {}

        try {
            var resolver = new PathMatchingResourcePatternResolver();
            // Seed only canonical class metadata
            Resource[] resources = resolver.getResources("classpath*:metadata/classes/*.json");
            System.out.println("TestMetadataSeeder found class metadata resources: " + resources.length);
            for (Resource r : resources) {
                try (InputStream is = r.getInputStream()) {
                    String txt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    JsonNode root = mapper.readTree(txt);
                    String className = null;
                    if (root.has("class")) className = root.get("class").asText();
                    else if (root.has("workClassReference")) className = root.get("workClassReference").asText();
                    else if (root.has("workClass")) className = root.get("workClass").asText();

                    String entityType = null;
                    if (root.has("entityType")) entityType = root.get("entityType").asText();
                    if (entityType == null || entityType.isBlank()) entityType = className;

                    if (className == null || className.isBlank()) {
                        System.out.println("Skipping metadata resource (no class): " + r.getFilename());
                        continue;
                    }

                    Integer cnt = 0;
                    try {
                        cnt = jdbc.queryForObject("SELECT count(*) FROM sys_expose_class_def WHERE entity_type = ?", Integer.class, entityType);
                    } catch (Exception sqe) {
                        // table might not exist or query failed
                        System.out.println("DEBUG: count query failed for " + entityType + ": " + sqe.getMessage());
                    }

                    if (cnt == null || cnt == 0) {
                        // insert full file content as json_definition
                        jdbc.update("INSERT INTO sys_expose_class_def(class_name, entity_type, json_definition, enabled, version) VALUES(?, ?, ?, ?, ?)",
                                new Object[]{className, entityType, txt, true, 1});
                        System.out.println("Inserted metadata for class=" + className + " entityType=" + entityType + " from resource=" + r.getFilename());
                    } else {
                        System.out.println("Metadata already present for entityType=" + entityType + ", skipping: " + r.getFilename());
                    }
                } catch (Exception ex) {
                    System.out.println("DEBUG: failed to process resource " + r.getFilename() + ": " + ex.getMessage());
                }
            }
        } catch (Exception e) {
            System.out.println("DEBUG: failed to seed sys_expose_class_def: " + e.getMessage());
        }
    }
}
