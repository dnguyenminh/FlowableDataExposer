package vn.com.fecredit.flowable.exposer.util;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Image helpers for model thumbnails. Split into focused helpers to
 * satisfy method-length constraints and improve testability.
 */
public final class ModelImageHelpers {
    private ModelImageHelpers() {}

    public static byte[] enhanceThumbnail(byte[] pngBytes) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (src == null) return pngBytes;
        if (!src.getColorModel().hasAlpha()) return pngBytes;
        BufferedImage out = toRgbCanvas(src);
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            ImageIO.write(out, "png", baos);
            return baos.toByteArray();
        }
    }

    private static BufferedImage toRgbCanvas(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            fillBackground(g, out.getWidth(), out.getHeight());
            g.drawImage(src, 0, 0, null);
            drawFocusBoxIfNeeded(g, src);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static void fillBackground(java.awt.Graphics2D g, int w, int h) {
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
    }

    private static void drawFocusBoxIfNeeded(java.awt.Graphics2D g, BufferedImage src) {
        int[] bounds = findOpaqueBounds(src);
        if (bounds == null) return;
        int w = src.getWidth();
        int h = src.getHeight();
        int minX = bounds[0], minY = bounds[1], maxX = bounds[2], maxY = bounds[3];
        int pad = Math.max(2, Math.min(12, Math.max(w, h) / 200));
        int bx = Math.max(0, minX - pad);
        int by = Math.max(0, minY - pad);
        int bw = Math.min(w - 1, maxX + pad) - bx + 1;
        int bh = Math.min(h - 1, maxY + pad) - by + 1;

        java.awt.BasicStroke stroke = new java.awt.BasicStroke(Math.max(1f, Math.min(3f, Math.max(w, h) / 500f)));
        g.setStroke(stroke);
        g.setColor(new java.awt.Color(0, 0, 0, 48));
        g.drawRect(bx + 1, by + 1, bw, bh);
        g.setColor(new java.awt.Color(0, 0, 0, 96));
        g.drawRect(bx, by, bw, bh);
    }

    private static int[] findOpaqueBounds(BufferedImage src) {
        final int w = src.getWidth();
        final int h = src.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                if (a > 16) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return null;
        return new int[]{minX, minY, maxX, maxY};
    }

    public static boolean isMostlyBlank(BufferedImage img) {
        if (img == null) return true;
        final int w = img.getWidth();
        final int h = img.getHeight();
        int stepX = Math.max(1, w / 200);
        int stepY = Math.max(1, h / 120);
        long blank = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = (rgb) & 0xff;
                if (r > 240 && g > 240 && b > 240) blank++;
            }
        }
        double ratio = (double) blank / ((w / stepX) * (h / stepY));
        return ratio > 0.98d;
    }
}
