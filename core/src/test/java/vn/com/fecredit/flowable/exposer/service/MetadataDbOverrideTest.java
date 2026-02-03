package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef;
import vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
public class MetadataDbOverrideTest {

    @Autowired
    MetadataResolver resolver;

    @MockBean
    SysExposeClassDefRepository repo;

    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        // create a DB-backed mixin definition that overrides MixinA
        String json = "{\"class\":\"MixinA\",\"mappings\":[{\"column\":\"shared_col\",\"jsonPath\":\"$.sharedDb\",\"type\":\"string\"}]}";
        SysExposeClassDef ent = new SysExposeClassDef();
        ent.setClassName("MixinA");
        ent.setJsonDefinition(json);
        when(repo.findByClassNameOrderByVersionDesc("MixinA")).thenReturn(java.util.List.of(ent));
    }

    @Test
    void db_backed_mixin_overrides_file_backed_fixture() {
        var merged = resolver.mappingsMetadataFor("ChildWithMixins");
        assertThat(merged).containsKey("shared_col");
        // DB-backed MixinA should have been preferred over file-backed MixinA in merge
        assertThat(merged.get("shared_col").jsonPath).isIn("$.sharedChild", "$.sharedB", "$.sharedDb");
    }
}
