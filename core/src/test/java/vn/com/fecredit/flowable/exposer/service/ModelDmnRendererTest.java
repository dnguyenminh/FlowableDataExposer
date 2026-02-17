package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import vn.com.fecredit.flowable.exposer.util.ModelDmnRenderer;

public class ModelDmnRendererTest {

    @Test
    public void renderPlaceholderProducesReadablePng() throws Exception {
        List<String> lines = List.of("First line", "Second line", "Third line");
        byte[] png = ModelDmnRenderer.renderPlaceholder("Sample Title", lines, 300, 120);
        assertNotNull(png);
        assertTrue(png.length > 0);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img);
        assertTrue(img.getWidth() > 0 && img.getHeight() > 0);
    }

    @Test
    public void renderDecisionTableProducesReadablePng() throws Exception {
        String[] headers = new String[]{"A", "B", "C"};
        String[][] cells = new String[][]{
                {"1", "x", "true"},
                {"2", "y", "false"},
        };
        byte[] png = ModelDmnRenderer.renderDecisionTable(headers, cells, 400, 160, "Decisions");
        assertNotNull(png);
        assertTrue(png.length > 0);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img);
        assertTrue(img.getWidth() > 0 && img.getHeight() > 0);
    }
}
