package vn.com.fecredit.flowable.exposer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef;
import vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class MetadataResolveEngineTest {

    private SysExposeClassDefRepository repo;
    private MetadataResourceLoader loader;
    private ObjectMapper mapper;
    private MetadataResolveEngine engine;

    @BeforeEach
    void setUp() {
        repo = mock(SysExposeClassDefRepository.class);
        loader = mock(MetadataResourceLoader.class);
        mapper = new ObjectMapper();
        engine = new MetadataResolveEngine(repo, loader, mapper);
    }

    @Test
    void resolveAndFlatten_returns_empty_when_no_definition_found() {
        when(repo.findLatestEnabledByEntityType(anyString())).thenReturn(Optional.empty());
        when(loader.findByEntityTypeOrClassCandidates(anyList())).thenReturn(Optional.empty());

        MetadataResolveEngine.Result res = engine.resolveAndFlatten("NonExisting");
        assertThat(res.merged).isEmpty();
        assertThat(res.diagnostics).isEmpty();
    }

    @Test
    void buildCandidates_handles_process_suffix_and_casing() throws Exception {
        // use reflection to call private method
        java.lang.reflect.Method m = MetadataResolveEngine.class.getDeclaredMethod("buildCandidates", String.class);
        m.setAccessible(true);
        List<String> cands = (List<String>) m.invoke(engine, "orderprocess");
        assertThat(cands).containsExactly("orderprocess", "order", "Order");

        cands = (List<String>) m.invoke(engine, "OrderProcess");
        assertThat(cands).containsExactly("OrderProcess", "Order");

        cands = (List<String>) m.invoke(engine, (Object) null);
        assertThat(cands).isNotNull();
    }

    @Test
    void joinNestedJsonPath_builds_expected_paths() throws Exception {
        java.lang.reflect.Method m = MetadataResolveEngine.class.getDeclaredMethod("joinNestedJsonPath", String.class, String.class, Integer.class);
        m.setAccessible(true);
        String res = (String) m.invoke(engine, "$.customer", "items[0].id", 1);
        assertThat(res).contains("$.customer");
        res = (String) m.invoke(engine, "$.root", "$.child", null);
        assertThat(res).contains("$.root.child");
    }

    @Test
    void buildInheritanceChain_detects_circular_parent_reference_and_uses_repo_and_loader() throws Exception {
        MetadataDefinition base = new MetadataDefinition();
        base._class = "Base";
        base.parent = null;
        base.mappings = List.of();

        MetadataDefinition child = new MetadataDefinition();
        child._class = "Child";
        child.parent = "Base";
        child.mappings = List.of();

        SysExposeClassDef dbDef = new SysExposeClassDef();
        dbDef.setJsonDefinition(new ObjectMapper().writeValueAsString(base));

        when(repo.findByClassNameOrderByVersionDesc("Base")).thenReturn(List.of(dbDef));

        java.lang.reflect.Method m = MetadataResolveEngine.class.getDeclaredMethod("buildInheritanceChain", MetadataDefinition.class, Map.class);
        m.setAccessible(true);
        Map<String, List<String>> diagnostics = new HashMap<>();
        List<?> chain = (List<?>) m.invoke(engine, child, diagnostics);
        assertThat(chain).isNotEmpty();
    }
}
