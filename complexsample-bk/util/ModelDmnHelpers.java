package vn.com.fecredit.complexsample.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Facade for DMN helpers: delegates extraction and rendering to smaller classes.
 *
 * This class has been refactored so each public helper delegates to short,
 * focused private helpers to satisfy method-length rules.
 */
public final class ModelDmnHelpers {
    private ModelDmnHelpers() {}

    public static byte[] createDmnPlaceholder(Object dmnModel, int width, int height) throws IOException {
        TitleLines tl = extractTitleAndLines(dmnModel);
        if (tl.lines.isEmpty()) tl.lines.add("(no decision table diagram available)");
        return ModelDmnRenderer.renderPlaceholder(tl.title, tl.lines, width, height);
    }

    public static byte[] createDmnDecisionTableImage(Object dmnModel, int width, int height) throws IOException {
        TableSlice slice = extractTableSlice(dmnModel, 8, 20);
        if (slice == null) return null;
        return ModelDmnRenderer.renderDecisionTable(slice.headers, slice.cells, width, height, slice.title);
    }

    public static byte[] createDmnDecisionTableImageFromXml(byte[] xmlBytes, int width, int height) throws IOException {
        ModelDmnXmlExtractor.TableData td = ModelDmnXmlExtractor.extract(xmlBytes, 8, 20);
        if (td == null) return null;
        return ModelDmnRenderer.renderDecisionTable(td.headers, td.cells, width, height, null);
    }

    public static boolean isMostlyBlank(java.awt.image.BufferedImage img) {
        return ModelImageHelpers.isMostlyBlank(img);
    }

    /* --- Private helpers (kept short) --- */
    private static TitleLines extractTitleAndLines(Object dmnModel) {
        String title = "DMN";
        List<String> lines = new ArrayList<>();
        try {
            List<Object> decisions = safeInvokeList(dmnModel, "getDecisions");
            if (decisions == null || decisions.isEmpty()) return new TitleLines(title, lines);
            Object dec = decisions.get(0);
            title = safeInvokeString(dec, "getName", title);
            Object table = safeInvoke(dec, "getDecisionTable");
            if (table == null) return new TitleLines(title, lines);
            List<Object> inputs = safeInvokeList(table, "getInputs");
            List<Object> outputs = safeInvokeList(table, "getOutputs");
            List<Object> rules = safeInvokeList(table, "getRules");
            lines.add(String.format("inputs: %d", inputs == null ? 0 : inputs.size()));
            lines.add(String.format("outputs: %d", outputs == null ? 0 : outputs.size()));
            lines.add(String.format("rules: %d", rules == null ? 0 : rules.size()));
            if (inputs != null && !inputs.isEmpty()) lines.add(summarizeInputs(inputs, 3));
        } catch (Throwable ignored) {}
        return new TitleLines(title, lines);
    }

    private static TableSlice extractTableSlice(Object dmnModel, int maxCols, int maxRows) {
        try {
            List<Object> decisions = safeInvokeList(dmnModel, "getDecisions");
            if (decisions == null || decisions.isEmpty()) return null;
            Object dec = decisions.get(0);
            Object table = safeInvoke(dec, "getDecisionTable");
            if (table == null) return null;
            List<Object> inputs = safeInvokeList(table, "getInputs");
            List<Object> outputs = safeInvokeList(table, "getOutputs");
            List<Object> rules = safeInvokeList(table, "getRules");
            int inCount = inputs == null ? 0 : inputs.size();
            int outCount = outputs == null ? 0 : outputs.size();
            int colCount = inCount + outCount;
            int rowCount = rules == null ? 0 : rules.size();
            if (colCount == 0 || rowCount == 0) return null;
            int renderCols = Math.min(colCount, maxCols);
            int renderRows = Math.min(rowCount, maxRows);
            String[] headers = buildHeaders(inputs, outputs, renderCols);
            String[][] cells = buildCells(rules, renderCols, renderRows, inCount);
            String title = headers.length > 0 && headers[0] != null ? headers[0] : null;
            return new TableSlice(title, headers, cells);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String[] buildHeaders(List<Object> inputs, List<Object> outputs, int renderCols) {
        String[] headers = new String[renderCols];
        int inCount = inputs == null ? 0 : inputs.size();
        for (int i = 0; i < renderCols; i++) {
            if (i < inCount) headers[i] = safeInputName(inputs, i, "in" + (i + 1));
            else {
                int oi = i - inCount;
                headers[i] = safeOutputName(outputs, oi, "out" + (oi + 1));
            }
        }
        return headers;
    }

    private static String[][] buildCells(List<Object> rules, int renderCols, int renderRows, int inCount) {
        String[][] cells = new String[renderRows][renderCols];
        for (int r = 0; r < renderRows; r++) {
            Object rule = rules.get(r);
            List<Object> inEls = safeInvokeList(rule, "getInputEntries");
            List<Object> outEls = safeInvokeList(rule, "getOutputEntries");
            for (int c = 0; c < renderCols; c++) {
                if (c < inCount) cells[r][c] = safeTextFromList(inEls, c, "-");
                else cells[r][c] = safeTextFromList(outEls, c - inCount, "-");
            }
        }
        return cells;
    }

    private static String safeInputName(List<Object> inputs, int idx, String def) {
        try {
            if (inputs == null || idx >= inputs.size()) return def;
            Object expr = safeInvoke(inputs.get(idx), "getInputExpression");
            return safeInvokeString(expr, "getText", def);
        } catch (Throwable t) { return def; }
    }

    private static String safeOutputName(List<Object> outputs, int idx, String def) {
        try { if (outputs == null || idx >= outputs.size()) return def; Object o = outputs.get(idx); return safeInvokeString(o, "getName", def); } catch (Throwable t) { return def; }
    }

    private static String summarizeInputs(List<Object> inputs, int maxShow) {
        StringBuilder sb = new StringBuilder();
        sb.append("input(s): ");
        int shown = Math.min(maxShow, inputs.size());
        for (int i = 0; i < shown; i++) {
            sb.append(safeInvokeStringSafe(inputs.get(i), "getInputExpression", "getText", "?"));
            if (i < shown - 1) sb.append(", ");
        }
        return sb.toString();
    }

    private static String safeTextFromList(List<Object> list, int idx, String def) {
        try { if (list == null || idx >= list.size() || list.get(idx) == null) return def; return safeInvokeString(list.get(idx), "getText", def); } catch (Throwable t) { return def; }
    }

    private static List<Object> safeInvokeList(Object target, String method) {
        try {
            Object o = safeInvoke(target, method);
            if (o == null) return null;
            @SuppressWarnings("unchecked") List<Object> lst = (List<Object>) o;
            return lst;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object safeInvoke(Object target, String method) {
        if (target == null) return null;
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String safeInvokeString(Object target, String method, String def) {
        try { Object o = safeInvoke(target, method); return o == null ? def : o.toString(); } catch (Throwable t) { return def; }
    }

    private static String safeInvokeStringSafe(Object target, String getterObj, String getterText, String def) {
        try {
            Object obj = safeInvoke(target, getterObj);
            return safeInvokeString(obj, getterText, def);
        } catch (Throwable t) { return def; }
    }

    /* small value objects */
    private static final class TitleLines { final String title; final List<String> lines; TitleLines(String t, List<String> l) { this.title = t; this.lines = l; } }
    private static final class TableSlice { final String title; final String[] headers; final String[][] cells; TableSlice(String t, String[] h, String[][] c) { this.title = t; this.headers = h; this.cells = c; } }

    private static String truncate(String s, int max) { if (s == null) return ""; if (s.length() <= max) return s; return s.substring(0, max - 1) + "…"; }
    private static String elide(String s, int max) { if (s == null) return ""; s = s.replaceAll("\n", " ").trim(); if (s.length() <= max) return s; return s.substring(0, max - 1) + "…"; }
}
