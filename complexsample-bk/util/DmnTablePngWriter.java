package vn.com.fecredit.complexsample.util;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tiny, standalone DMN decision-table PNG generator used as a deterministic
 * fallback for CI/dev diagnostics. Exposes a single static method that
 * writes a readable table image from a DMN file (first decisionTable).
 */
public final class DmnTablePngWriter {
    private DmnTablePngWriter() {}

    public static Path writeDecisionTableAsPng(Path dmnXml, Path target) throws Exception {
        byte[] xml = Files.readAllBytes(dmnXml);
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        var doc = db.parse(new ByteArrayInputStream(xml));

        var tables = doc.getElementsByTagName("decisionTable");
        if (tables.getLength() == 0) tables = doc.getElementsByTagNameNS("http://www.omg.org/spec/DMN/20151101/dmn.xsd", "decisionTable");
        if (tables.getLength() == 0) {
            // write a simple placeholder
            return writeTextPlaceholder(target, "No decisionTable found in DMN: " + dmnXml.getFileName());
        }

        var table = (org.w3c.dom.Element) tables.item(0);
        java.util.List<String> inputs = new java.util.ArrayList<>();
        var inputEls = table.getElementsByTagName("input");
        for (int i = 0; i < inputEls.getLength(); i++) {
            var in = (org.w3c.dom.Element) inputEls.item(i);
            String label = in.getAttribute("label");
            if (label == null || label.isBlank()) {
                var exprs = in.getElementsByTagName("inputExpression");
                if (exprs.getLength() > 0) {
                    var ie = (org.w3c.dom.Element) exprs.item(0);
                    var texts = ie.getElementsByTagName("text");
                    if (texts.getLength() > 0) label = texts.item(0).getTextContent();
                }
            }
            inputs.add(label == null || label.isBlank() ? ("in" + (i + 1)) : label.trim());
        }

        java.util.List<String> outputs = new java.util.ArrayList<>();
        var outputEls = table.getElementsByTagName("output");
        for (int i = 0; i < outputEls.getLength(); i++) {
            var outEl = (org.w3c.dom.Element) outputEls.item(i);
            String name = outEl.getAttribute("name");
            outputs.add(name == null || name.isBlank() ? ("out" + (i + 1)) : name.trim());
        }

        java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
        var ruleEls = table.getElementsByTagName("rule");
        for (int r = 0; r < ruleEls.getLength(); r++) {
            var rule = (org.w3c.dom.Element) ruleEls.item(r);
            var row = new java.util.ArrayList<String>();
            var inEntries = rule.getElementsByTagName("inputEntry");
            for (int i = 0; i < inputs.size(); i++) {
                if (i < inEntries.getLength()) row.add(inEntries.item(i).getTextContent().trim()); else row.add("-");
            }
            var outEntries = rule.getElementsByTagName("outputEntry");
            for (int i = 0; i < outputs.size(); i++) {
                if (i < outEntries.getLength()) row.add(outEntries.item(i).getTextContent().trim()); else row.add("-");
            }
            rows.add(row);
        }

        // render a wide, high-contrast table (fills most of the image)
        int W = 1200, H = Math.max(360, 40 + 26 * (rows.size() + 2));
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);

            int cols = inputs.size() + outputs.size();
            int tableX = 20, tableY = 40;
            int availW = W - tableX - 20;
            int colW = Math.max(80, availW / Math.max(1, cols));
            int headerH = 34, rowH = 28;

            Font titleF = new Font("SansSerif", Font.BOLD, 20);
            Font hdrF = new Font("SansSerif", Font.BOLD, 14);
            Font cellF = new Font("SansSerif", Font.PLAIN, 13);

            g.setColor(Color.BLACK);
            g.setFont(titleF);
            String title = "Decision Table — " + (inputs.size() > 0 ? inputs.get(0) : "");
            g.drawString(title, tableX, 26);

            // header
            int cx = tableX, cy = tableY;
            g.setColor(new Color(30, 60, 120));
            g.fillRect(cx, cy, colW * cols, headerH);
            g.setColor(Color.WHITE);
            g.setFont(hdrF);
            int tx = cx + 8;
            for (String in : inputs) {
                g.drawString(truncate(in, 20), tx, cy + headerH - 12);
                tx += colW;
            }
            for (String out : outputs) {
                g.drawString(truncate(out, 20), tx, cy + headerH - 12);
                tx += colW;
            }

            // rows
            g.setFont(cellF);
            cy += headerH;
            boolean alt = false;
            for (int r = 0; r < rows.size(); r++) {
                if (alt) g.setColor(new Color(245, 247, 255)); else g.setColor(Color.WHITE);
                g.fillRect(cx, cy, colW * cols, rowH);
                g.setColor(Color.DARK_GRAY);
                tx = cx + 8;
                var row = rows.get(r);
                for (int c = 0; c < cols; c++) {
                    String cell = c < row.size() ? row.get(c) : "-";
                    g.drawString(truncate(cell, 36), tx, cy + rowH - 10);
                    tx += colW;
                }
                cy += rowH;
                alt = !alt;
            }

            // grid
            g.setColor(new Color(200, 210, 230));
            for (int i = 0; i <= cols; i++) g.drawLine(tableX + i * colW, tableY, tableX + i * colW, tableY + headerH + rowH * rows.size());
            for (int r = 0; r <= rows.size(); r++) g.drawLine(tableX, tableY + r * rowH + (r==0?0:headerH), tableX + cols * colW, tableY + r * rowH + headerH);
        } finally {
            g.dispose();
        }

        Files.createDirectories(target.getParent());
        ImageIO.write(img, "png", target.toFile());
        return target;
    }

    private static Path writeTextPlaceholder(Path target, String text) throws Exception {
        int W = 1000, H = 300;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.BOLD, 18));
            g.drawString(text, 20, 40);
        } finally { g.dispose(); }
        Files.createDirectories(target.getParent());
        ImageIO.write(img, "png", target.toFile());
        return target;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\n", " ").trim();
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
