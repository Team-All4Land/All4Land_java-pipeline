package com.onnara.extract;

import com.onnara.extract.model.ExtractedCell;
import com.onnara.extract.model.ExtractedDocument;
import com.onnara.extract.model.ExtractedImage;
import com.onnara.extract.model.ExtractedTable;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HWPML(한/글 97~2002 시절 XML 내보내기 포맷, 보통 .hml 확장자) 파일 파서.
 *
 * Python hwpml_extractor.py와 동일한 로직을 표준 Java XML API(javax.xml.parsers)로
 * 그대로 옮긴 것 — 외부 의존성 없음.
 *
 * HWPML은 ZIP이 아니라 단일 XML 파일이고, 이미지도
 * &lt;BINDATASTORAGE&gt;&lt;BINDATA Encoding="Base64"&gt;...base64...&lt;/BINDATA&gt;
 * 형태로 XML 안에 직접 박혀있다 (압축 없음).
 * 표 셀 속성은 ColAddr/RowAddr/ColSpan/RowSpan (파스칼 케이스).
 */
public class HwpmlExtractor {

    public ExtractedDocument parse(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document xmlDoc = builder.parse(new File(path)); // BOM은 파서가 자동 처리

        ExtractedDocument doc = new ExtractedDocument(path);

        // 메타데이터: HEAD > DOCSUMMARY > TITLE / DATE
        NodeList docSummaries = xmlDoc.getElementsByTagName("DOCSUMMARY");
        if (docSummaries.getLength() > 0) {
            Element docSummary = (Element) docSummaries.item(0);
            Element titleEl = firstChildElement(docSummary, "TITLE");
            if (titleEl != null && titleEl.getTextContent() != null) {
                String t = titleEl.getTextContent().trim();
                if (!t.isEmpty()) doc.setTitle(t);
            }
            Element dateEl = firstChildElement(docSummary, "DATE");
            if (dateEl != null && dateEl.getTextContent() != null) {
                String d = dateEl.getTextContent().trim();
                if (!d.isEmpty()) doc.setCreatedDate(d);
            }
        }

        // 이미지 원본 데이터: BINDATASTORAGE > BINDATA (base64, 비압축)
        Map<String, byte[]> binDataMap = new HashMap<>();
        NodeList binDataList = xmlDoc.getElementsByTagName("BINDATA");
        for (int i = 0; i < binDataList.getLength(); i++) {
            Element bindata = (Element) binDataList.item(i);
            String bid = bindata.getAttribute("Id");
            String encoding = bindata.getAttribute("Encoding");
            String text = bindata.getTextContent();
            if (bid != null && !bid.isEmpty() && "base64".equalsIgnoreCase(encoding)
                    && text != null && !text.isEmpty()) {
                try {
                    binDataMap.put(bid, Base64.getDecoder().decode(text.trim()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        Set<Node> seenTables = new HashSet<>();
        Set<Node> seenImages = new HashSet<>();
        int tableSeq = 0;
        int imageSeq = 0;

        NodeList bodies = xmlDoc.getElementsByTagName("BODY");
        for (int b = 0; b < bodies.getLength(); b++) {
            Element body = (Element) bodies.item(b);
            NodeList sections = body.getElementsByTagName("SECTION");
            for (int s = 0; s < sections.getLength(); s++) {
                Element section = (Element) sections.item(s);

                List<Element> topParagraphs = directChildElements(section, "P");
                List<String> paraTexts = new ArrayList<>();
                for (Element p : topParagraphs) {
                    paraTexts.add(getText(p).trim());
                }

                for (int idx = 0; idx < topParagraphs.size(); idx++) {
                    Element p = topParagraphs.get(idx);
                    if (!paraTexts.get(idx).isEmpty()) {
                        doc.getParagraphs().add(paraTexts.get(idx));
                    }

                    for (Element tbl : descendantElements(p, "TABLE")) {
                        if (seenTables.contains(tbl)) continue;
                        seenTables.add(tbl);

                        ExtractedTable table = parseTable(tbl, "tbl_" + (tableSeq++));
                        table.setCaption(TextPatterns.nearestCaption(paraTexts, idx));
                        doc.getTables().add(table);

                        for (Element pic : descendantElements(tbl, "PICTURE")) {
                            if (seenImages.contains(pic)) continue;
                            seenImages.add(pic);
                            ExtractedImage img = parseImage(pic, binDataMap, imageSeq++);
                            if (img != null) doc.getImages().add(img);
                        }
                    }

                    for (Element pic : descendantElements(p, "PICTURE")) {
                        if (seenImages.contains(pic)) continue;
                        seenImages.add(pic);
                        ExtractedImage img = parseImage(pic, binDataMap, imageSeq++);
                        if (img != null) doc.getImages().add(img);
                    }
                }
            }
        }

        String guessedTitle = TextPatterns.guessTitleFromTables(doc.getTables());
        if (guessedTitle != null) {
            doc.setOriginalTitle(doc.getTitle());
            doc.setTitle(guessedTitle);
        }

        return doc;
    }

    // -- 표 파싱 -------------------------------------------------------------
    private ExtractedTable parseTable(Element tbl, String tableId) {
        int rowCount = parseIntAttr(tbl, "RowCount", 0);
        int colCount = parseIntAttr(tbl, "ColCount", 0);

        List<List<String>> grid = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < colCount; c++) row.add("");
            grid.add(row);
        }
        List<ExtractedCell> cells = new ArrayList<>();

        for (Element rowEl : directChildElements(tbl, "ROW")) {
            for (Element cellEl : directChildElements(rowEl, "CELL")) {
                int colAddr = parseIntAttr(cellEl, "ColAddr", 0);
                int rowAddr = parseIntAttr(cellEl, "RowAddr", 0);
                int colSpan = Math.max(1, parseIntAttr(cellEl, "ColSpan", 1));
                int rowSpan = Math.max(1, parseIntAttr(cellEl, "RowSpan", 1));

                StringBuilder cellTextBuilder = new StringBuilder();
                for (Element paralist : directChildElements(cellEl, "PARALIST")) {
                    for (Element p : directChildElements(paralist, "P")) {
                        String t = getText(p).trim();
                        if (!t.isEmpty()) {
                            if (cellTextBuilder.length() > 0) cellTextBuilder.append("\n");
                            cellTextBuilder.append(t);
                        }
                    }
                }
                String cellText = cellTextBuilder.toString();

                cells.add(new ExtractedCell(rowAddr, colAddr, rowSpan, colSpan, cellText));

                for (int r = rowAddr; r < Math.min(rowAddr + rowSpan, rowCount); r++) {
                    for (int c = colAddr; c < Math.min(colAddr + colSpan, colCount); c++) {
                        grid.get(r).set(c, cellText);
                    }
                }
            }
        }

        return new ExtractedTable(tableId, rowCount, colCount, grid, cells);
    }

    // -- 이미지 파싱 -----------------------------------------------------------
    private ExtractedImage parseImage(Element pic, Map<String, byte[]> binDataMap, int seq) {
        List<Element> images = descendantElements(pic, "IMAGE");
        if (images.isEmpty()) return null;
        Element imageEl = images.get(0);
        String binId = imageEl.getAttribute("BinItem");
        byte[] data = binDataMap.get(binId);
        if (data == null) return null;
        return new ExtractedImage(String.valueOf(seq), binId, data);
    }

    // -- XML 헬퍼 -------------------------------------------------------------
    /** elem 내부 모든 &lt;CHAR&gt; 텍스트를 이어붙인다. &lt;TABLE&gt;/&lt;PICTURE&gt; 내부는 건너뜀. */
    private String getText(Element elem) {
        StringBuilder sb = new StringBuilder();
        walkText(elem, sb);
        return sb.toString();
    }

    private void walkText(Node node, StringBuilder sb) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String tag = child.getNodeName();
            if (tag.equals("CHAR")) {
                sb.append(child.getTextContent());
            } else if (tag.equals("TABLE") || tag.equals("PICTURE")) {
                continue;
            } else {
                walkText(child, sb);
            }
        }
    }

    private Element firstChildElement(Element parent, String tag) {
        List<Element> list = directChildElements(parent, tag);
        return list.isEmpty() ? null : list.get(0);
    }

    private List<Element> directChildElements(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && child.getNodeName().equals(tag)) {
                result.add((Element) child);
            }
        }
        return result;
    }

    private List<Element> descendantElements(Element root, String tag) {
        List<Element> result = new ArrayList<>();
        NodeList all = root.getElementsByTagName(tag);
        for (int i = 0; i < all.getLength(); i++) {
            result.add((Element) all.item(i));
        }
        return result;
    }

    private int parseIntAttr(Element elem, String attr, int defaultValue) {
        String v = elem.getAttribute(attr);
        if (v == null || v.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
