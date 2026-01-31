package vn.com.fecredit.chunkedupload.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModelValidatorRendererDmnTest {

    @Test
    void renderDecisionTable_shouldProduceNonBlankImage_forOrderRules() throws Exception {
        Path dmn = Path.of("src/main/resources/decisions/orderRules.dmn");
        Path out = Path.of("build/model-validator/orderRules.dmn.png");
        Path written = ModelValidatorRenderer.renderToPng(dmn, out);
        assertThat(written).exists();

        BufferedImage img = ImageIO.read(written.toFile());
        assertThat(img).isNotNull();
        // image should be reasonably large and not mostly blank
        assertThat(img.getWidth()).isGreaterThanOrEqualTo(600);
        assertThat(img.getHeight()).isGreaterThanOrEqualTo(360);
        // sample pixels to ensure at least one is not near-white
        boolean foundNonWhite = false;
        int w = img.getWidth();
        int h = img.getHeight();
        for (int yy = h/4; yy < h && !foundNonWhite; yy += Math.max(1, h/10)) {
            for (int xx = w/8; xx < w && !foundNonWhite; xx += Math.max(1, w/10)) {
                int rgb = img.getRGB(xx, yy);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = (rgb) & 0xff;
                if (!(r > 240 && g > 240 && b > 240)) foundNonWhite = true;
            }
        }

        // If the Flowable DRD generator produced a mostly-blank image, the XML-based
        // decision-table renderer will be present as an additional artifact — accept
        // either the main PNG or the table PNG as evidence the decision table is visible.
        Path table = out.getParent().resolve(out.getFileName().toString().replaceAll("(?i)\\.png$", "") + ".table.png");
        if (!foundNonWhite) {
            // main image is blank -> ensure table.png exists and is non-blank
            assertThat(table).exists();
            BufferedImage tableImg = ImageIO.read(table.toFile());
            assertThat(tableImg).isNotNull();
            boolean headerNonWhite = false;
            for (int y = 10; y < Math.min(80, tableImg.getHeight()) && !headerNonWhite; y += 8) {
                for (int x = 10; x < Math.min(300, tableImg.getWidth()) && !headerNonWhite; x += 20) {
                    int rgb = tableImg.getRGB(x, y);
                    int rt = (rgb >> 16) & 0xff;
                    int gt = (rgb >> 8) & 0xff;
                    int bt = (rgb) & 0xff;
                    if (!(rt > 240 && gt > 240 && bt > 240)) headerNonWhite = true;
                }
            }
            assertThat(headerNonWhite).isTrue();

            // ensure at least one data cell (below header) contains visible text
            boolean dataNonWhite = false;
            int startY = Math.max(80, tableImg.getHeight() / 6);
            for (int y = startY; y < Math.min(tableImg.getHeight() - 10, startY + 200) && !dataNonWhite; y += 6) {
                for (int x = 20; x < Math.min(tableImg.getWidth() - 20, 400) && !dataNonWhite; x += 12) {
                    int rgb = tableImg.getRGB(x, y);
                    int rt = (rgb >> 16) & 0xff;
                    int gt = (rgb >> 8) & 0xff;
                    int bt = (rgb) & 0xff;
                    if (!(rt > 240 && gt > 240 && bt > 240)) dataNonWhite = true;
                }
            }
            assertThat(dataNonWhite).isTrue();
        } else {
            // main image already contains visible content; table artifact is optional but should exist when decisionTable present
            if (table.toFile().exists()) {
                BufferedImage tableImg = ImageIO.read(table.toFile());
                assertThat(tableImg).isNotNull();
            }
        }
    }
}
