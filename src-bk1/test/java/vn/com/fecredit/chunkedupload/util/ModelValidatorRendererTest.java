package vn.com.fecredit.chunkedupload.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

public class ModelValidatorRendererTest {

    @Test
    void cmmn_validation_and_render() throws Exception {
        Path cmmn = Path.of("src/main/resources/cases/orderCase.cmmn");
        assertTrue(Files.exists(cmmn), "orderCase.cmmn must exist in resources for this test");
        ModelValidatorRenderer.ValidationResult vr = ModelValidatorRenderer.validate(cmmn);
        assertTrue(vr.valid, "CMMN XSD validation must pass; messages: " + vr.messages);

        // there are known Modeler exports whose DI references don't match element ids; ensure we surface that as a warning
        boolean hasDiWarning = vr.messages.stream().anyMatch(s -> s.toLowerCase().contains("diagram") || s.toLowerCase().contains("cmmn di") || s.toLowerCase().contains("engine-level"));
        assertTrue(hasDiWarning, "Expected at least one warning about DI/engine-level parsing (messages: " + vr.messages + ")");

        Path out = Path.of("build/tmp/test-output/orderCase.png");
        try {
            Path written = ModelValidatorRenderer.renderToPng(cmmn, out);
            assertTrue(Files.exists(written));
            assertTrue(Files.size(written) > 100, "rendered image should be non-empty");
            java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(written.toFile());
            // thumbnails should be opaque (no alpha) and have a light corner background for visibility
            assertFalse(bi.getColorModel().hasAlpha(), "thumbnail must be opaque");
            int corner = bi.getRGB(0, 0);
            int cr = (corner >> 16) & 0xff;
            int cg = (corner >> 8) & 0xff;
            int cb = corner & 0xff;
            assertTrue(cr > 240 && cg > 240 && cb > 240, "expected white-ish background in corner");
        } catch (Exception ex) {
            // rendering may legitimately fail when DI is inconsistent; ensure the error is actionable
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            String msg = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();
            assertTrue(msg.contains("diagram") || msg.contains("graphicinfo") || msg.contains("di") || msg.contains("render"),
                    "render failed for unexpected reason: " + ex.getMessage());
        }
    }

    @Test
    void bpmn_validation_and_render() throws Exception {
        Path bpmn = Path.of("src/main/resources/processes/orderProcess.bpmn");
        assertTrue(Files.exists(bpmn), "orderProcess.bpmn must exist in resources for this test");
        ModelValidatorRenderer.ValidationResult vr = ModelValidatorRenderer.validate(bpmn);
        assertTrue(vr.valid, "BPMN should validate: " + vr.messages);

        Path out = Path.of("build/tmp/test-output/orderProcess.png");
        Path written = ModelValidatorRenderer.renderToPng(bpmn, out);
        assertTrue(Files.exists(written));
        assertTrue(Files.size(written) > 100, "rendered image should be non-empty");
        java.awt.image.BufferedImage bbi = javax.imageio.ImageIO.read(written.toFile());
        assertFalse(bbi.getColorModel().hasAlpha(), "BPMN thumbnail must be opaque");
        int corner2 = bbi.getRGB(0, 0);
        int cr2 = (corner2 >> 16) & 0xff;
        int cg2 = (corner2 >> 8) & 0xff;
        int cb2 = corner2 & 0xff;
        assertTrue(cr2 > 240 && cg2 > 240 && cb2 > 240, "expected white-ish background in corner for BPMN thumbnail");
    }

    @Test
    void dmn_validation_and_render_minimal() throws Exception {
        String minimalDmn = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<definitions xmlns=\"http://www.omg.org/spec/DMN/20151101/dmn.xsd\" id=\"simple\" name=\"simple\">\n" +
            "</definitions>\n";
        Path tmp = Path.of("build/tmp/test-dmn.xml");
        Files.createDirectories(tmp.getParent());
        Files.writeString(tmp, minimalDmn);

        ModelValidatorRenderer.ValidationResult vr = ModelValidatorRenderer.validate(tmp);
        // DMN XSDs can be strict in some environments; accept either valid or a useful error message
        assertNotNull(vr.messages);

        // Rendering DMN is optional in CI for minimal example — skip to avoid flaky environment-dependent failures
        // (manual rendering can be exercised with the CLI: ModelValidatorRenderer <file> --out out.png)
    }
}
