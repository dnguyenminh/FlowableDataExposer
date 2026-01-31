package vn.com.fecredit.chunkedupload.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DmnTablePngWriterTest {

    @Test
    void writeDecisionTableAsPng_writesReadableTable_forOrderRules() throws Exception {
        Path src = Path.of("src/main/resources/decisions/orderRules.dmn");
        Path out = Path.of("build/model-validator/orderRules.dmn.png");

        Path written = DmnTablePngWriter.writeDecisionTableAsPng(src, out);
        assertThat(written).exists();

        BufferedImage img = ImageIO.read(written.toFile());
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isGreaterThanOrEqualTo(600);
        assertThat(img.getHeight()).isGreaterThanOrEqualTo(200);

        // sample a few pixels including header area to ensure non-white content
        boolean nonWhite = false;
        for (int y = 10; y < Math.min(100, img.getHeight()); y += 10) {
            for (int x = 10; x < Math.min(300, img.getWidth()); x += 20) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = (rgb) & 0xff;
                if (!(r > 240 && g > 240 && b > 240)) {
                    nonWhite = true;
                    break;
                }
            }
            if (nonWhite) break;
        }
        assertThat(nonWhite).isTrue();

        // verify zebra-striping: sample two adjacent data-row horizontal bands and
        // ensure their background luminance differs (i.e. alternating tint present)
        int headerBottom = 36;
        int scanStart = Math.min(img.getHeight() - 20, headerBottom + 10);
        int scanEnd = Math.min(img.getHeight() - 10, scanStart + 200);
        // compute average brightness per scanline and look for two nearby scanlines with different brightness
        int[] lum = new int[scanEnd - scanStart + 1];
        for (int y = scanStart; y <= scanEnd; y++) {
            long sum = 0; int count = 0;
            for (int x = img.getWidth() / 6; x < Math.min(img.getWidth() - 20, img.getWidth() / 2); x += 4) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = (rgb) & 0xff;
                sum += (r + g + b) / 3;
                count++;
            }
            lum[y - scanStart] = count == 0 ? 255 : (int)(sum / count);
        }
        boolean foundStripe = false;
        for (int i = 0; i + 6 < lum.length && !foundStripe; i++) {
            int a = lum[i];
            int b = lum[i + 6];
            if (Math.abs(a - b) >= 6) foundStripe = true;
        }
        assertThat(foundStripe).isTrue();
    }
}
