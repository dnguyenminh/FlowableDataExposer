package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import vn.com.fecredit.flowable.exposer.util.ModelRenderHelpers;

public class ModelRenderHelpersTest {

    @Test
    public void renderToPng_throwsWhenFlowableMissing_orUnsupported() throws Exception {
        // The environment running tests does not include Flowable diagram generators.
        // Ensure the helper reports this as an IOException rather than crashing.
        Path tmp = Files.createTempFile("model-test", ".xml");
        try {
            Files.writeString(tmp, "<definitions></definitions>");
            assertThrows(IOException.class, () -> ModelRenderHelpers.renderToPng(tmp, tmp.resolveSibling("out.png")));
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(tmp.resolveSibling("out.png"));
        }
    }
}
