package vn.com.fecredit.flowable.exposer.util;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DMN-specific rendering helpers extracted from ModelValidatorRenderer to reduce file size.
 */
public final class ModelDmnHelpers {
    private ModelDmnHelpers() {}

    public static byte[] createDmnPlaceholder(Object dmnModel, int width, int height) throws IOException {
        String title = "DMN";
        List<String> lines = new ArrayList<>();
        try {
            java.lang.reflect.Method getDecisions = dmnModel.getClass().getMethod("getDecisions");
            @SuppressWarnings("unchecked")
            List<Object> decisions = (List<Object>) getDecisions.invoke(dmnModel);
            if (decisions != null && !decisions.isEmpty()) {
                Object dec = decisions.get(0);
                try {
                    java.lang.reflect.Method getName = dec.getClass().getMethod("getName");
                    Object nm = getName.invoke(dec);
                    if (nm != null) title = nm.toString();
                } catch (NoSuchMethodException ignore) {}

                try {
                    java.lang.reflect.Method getDecisionTable = dec.getClass().getMethod("getDecisionTable");
                    Object table = getDecisionTable.invoke(dec);
                    if (table != null) {
                        try {
                            java.lang.reflect.Method getInputs = table.getClass().getMethod("getInputs");
                            java.lang.reflect.Method getOutputs = table.getClass().getMethod("getOutputs");
                            java.lang.reflect.Method getRules = table.getClass().getMethod("getRules");
                            @SuppressWarnings("unchecked")
                            List<Object> inputs = (List<Object>) getInputs.invoke(table);
                            @SuppressWarnings("unchecked")
                            List<Object> outputs = (List<Object>) getOutputs.invoke(table);
                            @SuppressWarnings("unchecked")
                            List<Object> rules = (List<Object>) getRules.invoke(table);
                            lines.add(String.format("inputs: %d", inputs == null ? 0 : inputs.size()));
                            lines.add(String.format("outputs: %d", outputs == null ? 0 : outputs.size()));
                            lines.add(String.format("rules: %d", rules == null ? 0 : rules.size()));

                            if (inputs != null && !inputs.isEmpty()) {
                                StringBuilder sb = new StringBuilder();
                                sb.append("input(s): ");
                                int shown = Math.min(3, inputs.size());
                                for (int i = 0; i < shown; i++) {
                                    try {
                                        java.lang.reflect.Method inName = inputs.get(i).getClass().getMethod("getInputExpression");
                                        Object ie = inName.invoke(inputs.get(i));
                                        java.lang.reflect.Method textM = ie.getClass().getMethod("getText");
                                        Object txt = textM.invoke(ie);
                                        sb.append(txt == null ? "?" : txt.toString());
                                    } catch (Throwable ex) {
                                        sb.append("?");
                                    }
                                    if (i < shown - 1) sb.append(", ");
                                }
                                lines.add(sb.toString());
                            }
                        } catch (NoSuchMethodException ignored) {
                            // best-effort
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                    // skip
                }
            }
        } catch (NoSuchMethodException ignored) {
            // dmnModel does not expose expected methods; fallback to generic
        } catch (Throwable t) {
            // swallow — placeholder should never fail the renderer
        }

        if (lines.isEmpty()) lines.add("(no decision table diagram available)");

        int w = Math.max(600, width);
        int h = Math.max(360, height);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(java.awt.Color.DARK_GRAY);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, Math.max(18, w/32)));
            int y = 60;
            g.drawString(title, 40, y);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, Math.max(12, w/80)));
            y += 30;
            for (String ln : lines) {
                g.drawString(ln, 44, y);
                y += 22;
            }
            g.setColor(new java.awt.Color(0,0,0,40));
            g.drawRect(20, 30, w - 44, Math.min(h - 80, y - 20));
            g.setColor(new java.awt.Color(120,120,120));
            String hint = "Note: source DMN contains no DRD/DI — showing a summary instead";
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.ITALIC, Math.max(10, w/120)));
            g.drawString(hint, 40, h - 28);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    public static byte[] createDmnDecisionTableImage(Object dmnModel, int width, int height) throws IOException {
        try {
            java.lang.reflect.Method getDecisions = dmnModel.getClass().getMethod("getDecisions");
            @SuppressWarnings("unchecked")
            List<Object> decisions = (List<Object>) getDecisions.invoke(dmnModel);
            if (decisions == null || decisions.isEmpty()) return null;
            Object dec = decisions.get(0);
            java.lang.reflect.Method getDecisionTable = dec.getClass().getMethod("getDecisionTable");
            Object table = getDecisionTable.invoke(dec);
            if (table == null) return null;

            java.lang.reflect.Method getInputs = table.getClass().getMethod("getInputs");
            java.lang.reflect.Method getOutputs = table.getClass().getMethod("getOutputs");
            java.lang.reflect.Method getRules = table.getClass().getMethod("getRules");
            @SuppressWarnings("unchecked") List<Object> inputs = (List<Object>) getInputs.invoke(table);
            @SuppressWarnings("unchecked") List<Object> outputs = (List<Object>) getOutputs.invoke(table);
            @SuppressWarnings("unchecked") List<Object> rules = (List<Object>) getRules.invoke(table);
            if ((inputs == null || inputs.isEmpty()) && (outputs == null || outputs.isEmpty())) return null;

            int inCount = inputs == null ? 0 : inputs.size();
            int outCount = outputs == null ? 0 : outputs.size();
            int colCount = inCount + outCount;
            int rowCount = rules == null ? 0 : rules.size();
            if (colCount == 0 || rowCount == 0) return null;

            final int MAX_COLS = 8;
            final int MAX_ROWS = 20;
            int renderCols = Math.min(colCount, MAX_COLS);
            int renderRows = Math.min(rowCount, MAX_ROWS);

            String[] headers = new String[renderCols];
            for (int i = 0; i < renderCols; i++) {
                if (i < inCount) {
                    try {
                        java.lang.reflect.Method ie = inputs.get(i).getClass().getMethod("getInputExpression");
                        Object expr = ie.invoke(inputs.get(i));
                        java.lang.reflect.Method txtM = expr.getClass().getMethod("getText");
                        Object txt = txtM.invoke(expr);
                        headers[i] = txt == null ? "in" + (i+1) : txt.toString();
                    } catch (Throwable t) { headers[i] = "in" + (i+1); }
                } else {
                    int oi = i - inCount;
                    if (oi < outputs.size()) {
                        try {
                            java.lang.reflect.Method nameM = outputs.get(oi).getClass().getMethod("getName");
                            Object nm = nameM.invoke(outputs.get(oi));
                            headers[i] = nm == null ? "out" + (oi+1) : nm.toString();
                        } catch (Throwable t) { headers[i] = "out" + (oi+1); }
                    } else {
                        headers[i] = "col" + (i+1);
                    }
                }
            }

            String[][] cells = new String[renderRows][renderCols];
            for (int r = 0; r < renderRows; r++) {
                Object rule = rules.get(r);
                try {
                    java.lang.reflect.Method getInputEntries = rule.getClass().getMethod("getInputEntries");
                    java.lang.reflect.Method getOutputEntries = rule.getClass().getMethod("getOutputEntries");
                    @SuppressWarnings("unchecked") List<Object> inEls = (List<Object>) getInputEntries.invoke(rule);
                    @SuppressWarnings("unchecked") List<Object> outEls = (List<Object>) getOutputEntries.invoke(rule);
                    for (int c = 0; c < renderCols; c++) {
                        if (c < inCount) {
                            int idx = c;
                            if (inEls != null && idx < inEls.size() && inEls.get(idx) != null) {
                                try {
                                    java.lang.reflect.Method txtM = inEls.get(idx).getClass().getMethod("getText");
                                    Object txt = txtM.invoke(inEls.get(idx));
                                    cells[r][c] = txt == null ? "-" : txt.toString();
                                } catch (Throwable t) { cells[r][c] = "-"; }
                            } else {
                                cells[r][c] = "-";
                            }
                        } else {
                            int oi = c - inCount;
                            if (outEls != null && oi < outEls.size() && outEls.get(oi) != null) {
                                try {
                                    java.lang.reflect.Method txtM = outEls.get(oi).getClass().getMethod("getText");
                                    Object txt = txtM.invoke(outEls.get(oi));
                                    cells[r][c] = txt == null ? "-" : txt.toString();
                                } catch (Throwable t) { cells[r][c] = "-"; }
                            } else {
                                cells[r][c] = "-";
                            }
                        }
                    }
                } catch (NoSuchMethodException nsme) {
                    for (int c = 0; c < renderCols; c++) cells[r][c] = "?";
                }
            }

            int w = Math.max(600, width);
            int h = Math.max(360, height);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            try {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0,0,w,h);
                g.setColor(java.awt.Color.DARK_GRAY);
                int padding = 12;
                int tableX = 24;
                int tableY = 48;
                int availW = w - tableX - 24;

                java.awt.Font headerFont = new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14);
                java.awt.Font cellFont = new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 13);
                g.setFont(cellFont);
                java.awt.FontMetrics fmCell = g.getFontMetrics(cellFont);
                java.awt.FontMetrics fmHeader = g.getFontMetrics(headerFont);

                int[] colW = new int[renderCols];
                int minCol = 60;
                for (int c = 0; c < renderCols; c++) {
                    int mw = fmHeader.stringWidth(headers[c]) + padding * 2;
                    for (int r = 0; r < renderRows; r++) {
                        int sw = fmCell.stringWidth(truncate(cells[r][c], 60));
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

                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 18));
                String title = (headers != null && headers.length > 0 && headers[0] != null && !headers[0].isBlank())
                    ? headers[0] + " — decision table" : "Decision Table";
                 g.setColor(java.awt.Color.BLACK);
                 g.drawString(title, tableX, 30);

                int cx = tableX;
                int cy = tableY;
                g.setColor(new java.awt.Color(240,240,250));
                g.fillRect(cx, cy, totalW, fmHeader.getHeight() + 8);
                g.setColor(java.awt.Color.DARK_GRAY);
                g.setFont(headerFont);
                for (int c = 0; c < renderCols; c++) {
                    int tx = cx + 6;
                    int ty = cy + fmHeader.getDescent() - 6;
                    g.drawString(elide(headers[c], 24), tx, ty);
                    cx += colW[c];
                }

                g.setFont(cellFont);
                cy += headerH(fmHeader);
                for (int r = 0; r < renderRows; r++) {
                    cx = tableX;
                    if ((r & 1) == 0) {
                        g.setColor(new java.awt.Color(255,255,255));
                    } else {
                        g.setColor(new java.awt.Color(250,250,255));
                    }
                    g.fillRect(cx, cy, totalW, fmCell.getHeight() + 8);
                    g.setColor(java.awt.Color.DARK_GRAY);
                    for (int c = 0; c < renderCols; c++) {
                        int tx = cx + 6;
                        int ty = cy + fmCell.getAscent();
                        g.drawString(elide(cells[r][c], 40), tx, ty);
                        cx += colW[c];
                    }
                    cy += fmCell.getHeight() + 8;
                }

                g.setColor(new java.awt.Color(200,200,220));
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

                g.setColor(new java.awt.Color(100,100,120));
                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
                g.drawString("Rendered from DMN decision table (summary)", tableX, Math.min(h - 12, cy + 20));
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (NoSuchMethodException nsme) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int headerH(java.awt.FontMetrics fmHeader) { return Math.max(24, fmHeader.getHeight() + 8); }

    public static byte[] createDmnDecisionTableImageFromXml(byte[] xmlBytes, int width, int height) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            org.w3c.dom.Document doc = db.parse(new ByteArrayInputStream(xmlBytes));

            org.w3c.dom.NodeList tables = doc.getElementsByTagNameNS("http://www.omg.org/spec/DMN/20151101/dmn.xsd", "decisionTable");
            if (tables.getLength() == 0) {
                tables = doc.getElementsByTagName("decisionTable");
                if (tables.getLength() == 0) return null;
            }
            org.w3c.dom.Element table = (org.w3c.dom.Element) tables.item(0);

            java.util.List<String> inputs = new java.util.ArrayList<>();
            org.w3c.dom.NodeList inputEls = table.getElementsByTagName("input");
            for (int i = 0; i < inputEls.getLength(); i++) {
                org.w3c.dom.Element in = (org.w3c.dom.Element) inputEls.item(i);
                String label = in.getAttribute("label");
                if (label == null || label.isBlank()) {
                    org.w3c.dom.NodeList exprs = in.getElementsByTagName("inputExpression");
                    if (exprs.getLength() > 0) {
                        org.w3c.dom.Element ie = (org.w3c.dom.Element) exprs.item(0);
                        org.w3c.dom.NodeList texts = ie.getElementsByTagName("text");
                        if (texts.getLength() > 0) label = texts.item(0).getTextContent();
                    }
                }
                inputs.add(label == null || label.isBlank() ? ("in" + (i+1)) : label.trim());
            }

            java.util.List<String> outputs = new java.util.ArrayList<>();
            org.w3c.dom.NodeList outputEls = table.getElementsByTagName("output");
            for (int i = 0; i < outputEls.getLength(); i++) {
                org.w3c.dom.Element out = (org.w3c.dom.Element) outputEls.item(i);
                String name = out.getAttribute("name");
                outputs.add(name == null || name.isBlank() ? ("out" + (i+1)) : name.trim());
            }

            java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
            org.w3c.dom.NodeList ruleEls = table.getElementsByTagName("rule");
            for (int r = 0; r < ruleEls.getLength(); r++) {
                org.w3c.dom.Element rule = (org.w3c.dom.Element) ruleEls.item(r);
                java.util.List<String> row = new java.util.ArrayList<>();
                org.w3c.dom.NodeList inEntries = rule.getElementsByTagName("inputEntry");
                for (int i = 0; i < inputs.size(); i++) {
                    if (i < inEntries.getLength()) {
                        String txt = inEntries.item(i).getTextContent();
                        row.add(txt == null || txt.isBlank() ? "-" : txt.trim());
                    } else { row.add("-"); }
                }
                org.w3c.dom.NodeList outEntries = rule.getElementsByTagName("outputEntry");
                for (int i = 0; i < outputs.size(); i++) {
                    if (i < outEntries.getLength()) {
                        String txt = outEntries.item(i).getTextContent();
                        row.add(txt == null || txt.isBlank() ? "-" : txt.trim());
                    } else { row.add("-"); }
                }
                rows.add(row);
            }

            if (rows.isEmpty()) return null;

            int inCount = inputs.size();
            int outCount = outputs.size();
            int colCount = inCount + outCount;
            int rowCount = rows.size();

            final int MAX_COLS = 8;
            final int MAX_ROWS = 20;
            int renderCols = Math.min(colCount, MAX_COLS);
            int renderRows = Math.min(rowCount, MAX_ROWS);

            String[] headers = new String[renderCols];
            for (int i = 0; i < renderCols; i++) {
                if (i < inCount) headers[i] = inputs.get(i); else headers[i] = outputs.get(i - inCount);
            }

            String[][] cells = new String[renderRows][renderCols];
            for (int r = 0; r < renderRows; r++) {
                java.util.List<String> src = rows.get(r);
                for (int c = 0; c < renderCols; c++) {
                    cells[r][c] = c < src.size() ? src.get(c) : "-";
                }
            }

            int w = Math.max(600, width);
            int h = Math.max(360, height);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            try {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0,0,w,h);
                g.setColor(java.awt.Color.DARK_GRAY);
                int padding = 12;
                int tableX = 24;
                int tableY = 48;
                int availW = w - tableX - 24;

                java.awt.Font headerFont = new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14);
                java.awt.Font cellFont = new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 13);
                g.setFont(cellFont);
                java.awt.FontMetrics fmCell = g.getFontMetrics(cellFont);
                java.awt.FontMetrics fmHeader = g.getFontMetrics(headerFont);

                int[] colW = new int[renderCols];
                int minCol = 60;
                for (int c = 0; c < renderCols; c++) {
                    int mw = fmHeader.stringWidth(headers[c]) + padding * 2;
                    for (int r = 0; r < renderRows; r++) {
                        int sw = fmCell.stringWidth(truncate(cells[r][c], 60));
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

                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 18));
                g.drawString("Decision Table", tableX, 30);

                int cx = tableX, cy = tableY;
                g.setColor(new java.awt.Color(240,240,250));
                g.fillRect(cx, cy, totalW, fmHeader.getHeight() + 8);
                g.setColor(java.awt.Color.DARK_GRAY);
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
                        g.setColor(new java.awt.Color(255,255,255));
                    } else {
                        g.setColor(new java.awt.Color(250,250,255));
                    }
                    g.fillRect(cx, cy, totalW, fmCell.getHeight() + 8);
                    g.setColor(java.awt.Color.DARK_GRAY);
                    for (int c = 0; c < renderCols; c++) {
                        int tx = cx + 6;
                        int ty = cy + fmCell.getAscent();
                        g.drawString(elide(cells[r][c], 40), tx, ty);
                        cx += colW[c];
                    }
                    cy += fmCell.getHeight() + 8;
                }

                g.setColor(new java.awt.Color(200,200,220));
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

                g.setColor(new java.awt.Color(100,100,120));
                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
                g.drawString("Rendered from DMN decision table (summary)", tableX, Math.min(h - 12, cy + 20));
            } finally { g.dispose(); }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception ex) { return null; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static String elide(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\n", " ").trim();
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    public static boolean isMostlyBlank(BufferedImage img) {
        if (img == null) return true;
        final int w = img.getWidth();
        final int h = img.getHeight();
        long total = (long) w * h;
        long blank = 0;
        int stepX = Math.max(1, w / 200);
        int stepY = Math.max(1, h / 120);
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
