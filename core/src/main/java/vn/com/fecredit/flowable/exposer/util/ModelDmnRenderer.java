package vn.com.fecredit.flowable.exposer.util;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Rendering utilities for DMN decision-table images (headers + rows).
 */
public final class ModelDmnRenderer {
    private ModelDmnRenderer() {}

    public static byte[] renderPlaceholder(String title, java.util.List<String> lines, int width, int height) throws IOException {
        int w = Math.max(600, width);
        int h = Math.max(360, height);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.DARK_GRAY);
            g.setFont(new Font("SansSerif", Font.BOLD, Math.max(18, w/32)));
            int y = 60;
            g.drawString(title, 40, y);
            g.setFont(new Font("SansSerif", Font.PLAIN, Math.max(12, w/80)));
            y += 30;
            for (String ln : lines) {
                g.drawString(ln, 44, y);
                y += 22;
            }
            g.setColor(new Color(0,0,0,40));
            g.drawRect(20, 30, w - 44, Math.min(h - 80, y - 20));
            g.setColor(new Color(120,120,120));
            String hint = "Note: source DMN contains no DRD/DI — showing a summary instead";
            g.setFont(new Font("SansSerif", Font.ITALIC, Math.max(10, w/120)));
            g.drawString(hint, 40, h - 28);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    public static byte[] renderDecisionTable(String[] headers, String[][] cells, int width, int height, String title) throws IOException {
        int w = Math.max(600, width);
        int h = Math.max(360, height);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0,0,w,h);
            g.setColor(Color.DARK_GRAY);
            int padding = 12;
            int tableX = 24;
            int tableY = 48;
            int availW = w - tableX - 24;

            Font headerFont = new Font("SansSerif", Font.BOLD, 14);
            Font cellFont = new Font("SansSerif", Font.PLAIN, 13);
            g.setFont(cellFont);
            FontMetrics fmCell = g.getFontMetrics(cellFont);
            FontMetrics fmHeader = g.getFontMetrics(headerFont);

            int renderCols = headers.length;
            int renderRows = cells.length;

            int[] colW = new int[renderCols];
            int minCol = 60;
            for (int c = 0; c < renderCols; c++) {
                int mw = fmHeader.stringWidth(safe(headers[c])) + padding * 2;
                for (int r = 0; r < renderRows; r++) {
                    int sw = fmCell.stringWidth(truncate(safe(cells[r][c]), 60));
                    if (sw + padding * 2 > mw) mw = sw + padding * 2;
                }
                colW[c] = Math.max(minCol, mw);
            }
            int totalW = 0; for (int x : colW) totalW += x;
            if (totalW > availW) {
                double scale = (double) availW / (double) totalW;
                for (int c = 0; c < colW.length; c++) colW[c] = Math.max(40, (int)(colW[c] * scale));
                totalW = 0; for (int x : colW) totalW += x;
            }

            g.setFont(new Font("SansSerif", Font.BOLD, 18));
            String t = (title != null && !title.isBlank()) ? title : (headers.length > 0 && headers[0] != null ? headers[0] + " — decision table" : "Decision Table");
            g.setColor(Color.BLACK);
            g.drawString(t, tableX, 30);

            int cx = tableX;
            int cy = tableY;
            g.setColor(new Color(240,240,250));
            g.fillRect(cx, cy, totalW, fmHeader.getHeight() + 8);
            g.setColor(Color.DARK_GRAY);
            g.setFont(headerFont);
            for (int c = 0; c < renderCols; c++) {
                int tx = cx + 6;
                int ty = cy + fmHeader.getAscent();
                g.drawString(elide(headers[c], 24), tx, ty);
                cx += colW[c];
            }
            cy += fmHeader.getHeight() + 8;
            g.setFont(cellFont);
            for (int r = 0; r < renderRows; r++) {
                cx = tableX;
                if ((r & 1) == 0) {
                    g.setColor(new Color(255,255,255));
                } else {
                    g.setColor(new Color(250,250,255));
                }
                g.fillRect(cx, cy, totalW, fmCell.getHeight() + 8);
                g.setColor(Color.DARK_GRAY);
                for (int c = 0; c < renderCols; c++) {
                    int tx = cx + 6;
                    int ty = cy + fmCell.getAscent();
                    g.drawString(elide(cells[r][c], 40), tx, ty);
                    cx += colW[c];
                }
                cy += fmCell.getHeight() + 8;
            }

            g.setColor(new Color(200,200,220));
            int gx = tableX;
            int gy = tableY;
            for (int c = 0; c <= renderCols; c++) {
                g.drawLine(gx, gy, gx, gy + (fmHeader.getHeight() + 8) + (fmCell.getHeight() + 8) * renderRows);
                if (c < renderCols) gx += colW[c];
            }
            for (int r = 0; r <= renderRows; r++) {
                int yoff = tableY + (r == 0 ? 0 : (fmHeader.getHeight() + 8)) + r * (fmCell.getHeight() + 8);
                g.drawLine(tableX, yoff, tableX + totalW, yoff);
            }

            g.setColor(new Color(100,100,120));
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.drawString("Rendered from DMN decision table (summary)", tableX, Math.min(h - 12, cy + 20));
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String truncate(String s, int max) { if (s == null) return ""; if (s.length() <= max) return s; return s.substring(0, max - 1) + "…"; }
    private static String elide(String s, int max) { if (s == null) return ""; s = s.replaceAll("\n", " ").trim(); if (s.length() <= max) return s; return s.substring(0, max - 1) + "…"; }
}
