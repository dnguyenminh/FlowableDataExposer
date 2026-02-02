package vn.com.fecredit.flowable.exposer.service.metadata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository;

import java.util.Arrays;

@SpringBootApplication
public class MetadataImporterApplication {
    public static void main(String[] args) throws Exception {
        ApplicationContext ctx = SpringApplication.run(MetadataImporterApplication.class, args);
        boolean importFiles = Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("--importFiles"));
        if (!importFiles) {
            System.out.println("Usage: MetadataImporterApplication --importFiles");
            SpringApplication.exit(ctx, () -> 0);
            return;
        }

        SysExposeClassDefRepository repo = ctx.getBean(SysExposeClassDefRepository.class);
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath:metadata/*.json");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (var r : resources) {
            try (var is = r.getInputStream()) {
                String txt = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                MetadataDefinition def = mapper.readValue(txt, MetadataDefinition.class);
                var ent = new vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef();
                ent.setClassName(def._class);
                ent.setEntityType(def.entityType);
                ent.setVersion(def.version == null ? 1 : def.version);
                ent.setJsonDefinition(txt);
                ent.setEnabled(def.enabled == null ? true : def.enabled);
                repo.save(ent);
                System.out.println("Imported metadata: " + def._class + " v" + ent.getVersion());
            }
        }

        SpringApplication.exit(ctx, () -> 0);
    }
}
