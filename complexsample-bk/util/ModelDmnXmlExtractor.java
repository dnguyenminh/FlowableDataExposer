package vn.com.fecredit.complexsample.util;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts decision-table structure (headers + cells) from DMN XML.
 */
public final class ModelDmnXmlExtractor {
    private ModelDmnXmlExtractor() {}

    public static class TableData {
        public final String[] headers;
        public final String[][] cells;

        public TableData(String[] headers, String[][] cells) {
            this.headers = headers;
            this.cells = cells;
        }
    }

    public static TableData extract(byte[] xmlBytes, int maxCols, int maxRows) {
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

            List<String> inputs = new ArrayList<>();
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

            List<String> outputs = new ArrayList<>();
            org.w3c.dom.NodeList outputEls = table.getElementsByTagName("output");
            for (int i = 0; i < outputEls.getLength(); i++) {
                org.w3c.dom.Element out = (org.w3c.dom.Element) outputEls.item(i);
                String name = out.getAttribute("name");
                outputs.add(name == null || name.isBlank() ? ("out" + (i+1)) : name.trim());
            }

            List<List<String>> rows = new ArrayList<>();
            org.w3c.dom.NodeList ruleEls = table.getElementsByTagName("rule");
            for (int r = 0; r < ruleEls.getLength(); r++) {
                org.w3c.dom.Element rule = (org.w3c.dom.Element) ruleEls.item(r);
                List<String> row = new ArrayList<>();
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

            int renderCols = Math.min(colCount, maxCols);
            int renderRows = Math.min(rowCount, maxRows);

            String[] headers = new String[renderCols];
            for (int i = 0; i < renderCols; i++) {
                if (i < inCount) headers[i] = inputs.get(i);
                else headers[i] = outputs.get(i - inCount);
            }

            String[][] cells = new String[renderRows][renderCols];
            for (int r = 0; r < renderRows; r++) {
                List<String> src = rows.get(r);
                for (int c = 0; c < renderCols; c++) {
                    cells[r][c] = c < src.size() ? src.get(c) : "-";
                }
            }

            return new TableData(headers, cells);
        } catch (Exception ex) {
            return null;
        }
    }
}
