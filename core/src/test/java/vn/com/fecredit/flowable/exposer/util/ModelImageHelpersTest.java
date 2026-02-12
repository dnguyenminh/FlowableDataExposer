package vn.com.fecredit.flowable.exposer.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class ModelImageHelpersTest {

    @Test
    public void enhanceThumbnail_noAlpha_returnsSameBytes() throws Exception {
        BufferedImage img = new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        byte[] input = baos.toByteArray();

        byte[] out = ModelImageHelpers.enhanceThumbnail(input);
        assertArrayEquals(input, out, "RGB image without alpha should be returned unchanged");
    }

    @Test
    public void enhanceThumbnail_withAlpha_convertsToRgb() throws Exception {
        BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            // transparent background
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            // draw an opaque red rectangle
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(new Color(200, 0, 0, 255));
            g.fillRect(10, 10, 40, 30);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        byte[] input = baos.toByteArray();

        byte[] out = ModelImageHelpers.enhanceThumbnail(input);
        assertNotNull(out);
        BufferedImage res = ImageIO.read(new ByteArrayInputStream(out));
        assertNotNull(res);
        assertFalse(res.getColorModel().hasAlpha(), "Enhanced image should not have alpha channel");
        assertEquals(img.getWidth(), res.getWidth());
        assertEquals(img.getHeight(), res.getHeight());
    }

    @Test
    public void isMostlyBlank_trueForWhite() {
        BufferedImage img = new BufferedImage(200, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
        } finally {
            g.dispose();
        }
        assertTrue(ModelImageHelpers.isMostlyBlank(img));
    }

    @Test
    public void isMostlyBlank_falseForSingleDarkPixel() {
        BufferedImage img = new BufferedImage(200, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            img.setRGB(50, 30, Color.BLACK.getRGB());
        } finally {
            g.dispose();
        }
        assertFalse(ModelImageHelpers.isMostlyBlank(img));
    }
}
