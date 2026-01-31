package vn.com.fecredit.chunkedupload.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ModelValidatorRendererArtifactsTest {

    @Test
    void allDmnResources_mustProduceReadableThumbnailOrTableArtifact() throws Exception {
        try (Stream<Path> ds = Files.list(Path.of("src/main/resources/decisions")).filter(p -> p.toString().toLowerCase().endsWith(".dmn"))) {
            ds.forEach(dmn -> {
                try {
                    Path out = Path.of("build/model-validator").resolve(dmn.getFileName().toString() + ".png");
                    // ensure renderer can produce the artifacts (writes into build/model-validator)
                    Path written = ModelValidatorRenderer.renderToPng(dmn, out);
                    assertThat(written).exists();

                    BufferedImage img = ImageIO.read(written.toFile());
                    boolean mainHasVisibleContent = false;
                    if (img != null) {
                        // quick sanity: image dimensions and at least one non-white sample pixel
                        assertThat(img.getWidth()).isGreaterThanOrEqualTo(200);
                        assertThat(img.getHeight()).isGreaterThanOrEqualTo(120);
                        int w = img.getWidth();
                        int h = img.getHeight();
                        outer: for (int yy = Math.max(5, h/6); yy < h; yy += Math.max(1, h/8)) {
                            for (int xx = Math.max(5, w/8); xx < w; xx += Math.max(1, w/8)) {
                                int rgb = img.getRGB(xx, yy);
                                int r = (rgb >> 16) & 0xff;
                                int g = (rgb >> 8) & 0xff;
                                int b = (rgb) & 0xff;
                                if (!(r > 240 && g > 240 && b > 240)) {
                                    mainHasVisibleContent = true;
                                    break outer;
                                }
                            }
                        }
                    }

                    Path table = out.getParent().resolve(out.getFileName().toString().replaceAll("(?i)\\.png$", "") + ".table.png");
                    if (!mainHasVisibleContent) {
                        // require the presence of a non-blank table artifact when main PNG appears blank
                        assertThat(table).exists();
                        BufferedImage tableImg = ImageIO.read(table.toFile());
                        assertThat(tableImg).isNotNull();
                        boolean headerNonWhite = false;
                        for (int y = 4; y < Math.min(80, tableImg.getHeight()) && !headerNonWhite; y += 6) {
                            for (int x = 4; x < Math.min(300, tableImg.getWidth()) && !headerNonWhite; x += 20) {
                                int rgb = tableImg.getRGB(x, y);
                                int rt = (rgb >> 16) & 0xff;
                                int gt = (rgb >> 8) & 0xff;
                                int bt = (rgb) & 0xff;
                                if (!(rt > 240 && gt > 240 && bt > 240)) headerNonWhite = true;
                            }
                        }
                        assertThat(headerNonWhite).isTrue();
                    }

                } catch (Exception e) {
                    throw new RuntimeException("Rendering failed for DMN: " + dmn, e);
                }
            });
        }
    }
}
