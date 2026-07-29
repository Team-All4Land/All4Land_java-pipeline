package com.onnara.extract.engine.hml;

import com.onnara.extract.common.ImageFormats;
import com.onnara.extract.common.model.RawCell;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;
import com.onnara.extract.engine.Extractor;
import com.onnara.extract.engine.image.ImageSieve;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HML(HWPML — 한/글 XML 내보내기, .hml) 네이티브 추출 엔진 — JDK 내장 DOM 파싱.
 *
 * <p>TOBE 문서는 StAX를 언급하지만, 중첩 TABLE/PICTURE 중복 제거(identity set)와
 * PARALIST 셀 문단 복원이 필요한 이 포맷에는 검증된 DOM 로직을 유지한다
 * (외부 의존성 없음이라는 취지는 동일 — JDK 내장 API만 사용).
 * 파일은 UTF-8 BOM 포함 단일 XML이며 DOM 파서가 BOM을 자동 처리한다.
 *
 * <p>표 셀 속성 ColAddr/RowAddr/ColSpan/RowSpan을 {@link RawCell}로 정확히
 * 복원하고, grid는 span 범위를 셀 텍스트로 복제해 채운다.
 */
public class HmlExtractor implements Extractor {

    /** {@code hml} 확장자만 지원한다. */
    @Override
    public boolean supports(String ext) {
        return "hml".equals(ext);
    }

    /** 엔진 식별자 "hml-dom". */
    @Override
    public String engineName() {
        return "hml-dom";
    }

    /** 파일을 파싱해 raw 문서만 반환한다(이미지는 메타로만 포함). */
    @Override
    public RawDocument extractRaw(Path file) throws IOException {
        ParseResult result = parse(file);
        return result.raw;
    }

    /** base64 BINDATA에서 복원한 이미지들을 outDir에 쓰고 저장 경로 목록을 반환한다. */
    @Override
    public List<Path> saveImages(Path file, Path outDir) throws IOException {
        ParseResult result = parse(file);
        List<Path> saved = new ArrayList<>();
        for (ImageEntry entry : result.images) {
            Files.createDirectories(outDir);
            Path dest = outDir.resolve(entry.name);
            Files.write(dest, entry.data);
            saved.add(dest);
        }
        return saved;
    }

    /** 단일 파싱 패스 — extractRaw/saveImages의 이미지 순서·이름 일치를 보장한다. */
    private ParseResult parse(Path file) throws IOException {
        Document xmlDoc = readXml(file);
        RawDocument raw = new RawDocument(file.getFileName().toString(), "hml", false);
        List<ImageEntry> images = new ArrayList<>();
        String stem = stemOf(file);

        // 이미지 원본: BINDATASTORAGE > BINDATA (base64, 비압축)
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
                    binDataMap.put(bid, Base64.getMimeDecoder().decode(text.trim()));
                } catch (IllegalArgumentException ignored) {
                    // 손상된 base64는 건너뜀
                }
            }
        }

        Set<Node> seenTables = new HashSet<>();
        Set<Node> seenImages = new HashSet<>();

        NodeList bodies = xmlDoc.getElementsByTagName("BODY");
        for (int b = 0; b < bodies.getLength(); b++) {
            Element body = (Element) bodies.item(b);
            NodeList sections = body.getElementsByTagName("SECTION");
            for (int s = 0; s < sections.getLength(); s++) {
                Element section = (Element) sections.item(s);
                for (Element p : directChildElements(section, "P")) {
                    String text = getText(p).trim();
                    if (!text.isEmpty()) {
                        raw.getContent().add(new RawParagraph(text));
                    }

                    for (Element tbl : descendantElements(p, "TABLE")) {
                        if (!seenTables.add(tbl)) {
                            continue;
                        }
                        raw.getContent().add(parseTable(tbl));
                        for (Element pic : descendantElements(tbl, "PICTURE")) {
                            if (seenImages.add(pic)) {
                                addImage(images, pic, binDataMap, stem);
                            }
                        }
                    }

                    for (Element pic : descendantElements(p, "PICTURE")) {
                        if (seenImages.add(pic)) {
                            addImage(images, pic, binDataMap, stem);
                        }
                    }
                }
            }
        }

        for (ImageEntry entry : images) {
            raw.getImages().add(new RawImage(entry.name, entry.data.length));
        }
        return new ParseResult(raw, images);
    }

    /** HML을 DOM으로 읽는다. XXE 방어(DTD·외부 엔티티 차단)를 적용하며 실패 시 IOException. */
    private static Document readXml(Path file) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE 방어: DTD·외부 엔티티 비활성화 (HML은 DOCTYPE을 쓰지 않음)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(file.toFile());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("HML 파일을 읽을 수 없습니다: " + file, e);
        }
    }

    /**
     * PICTURE의 IMAGE BinItem이 가리키는 base64 원본을 찾아 이미지 목록에 추가한다.
     *
     * <p>정보가 없는 이미지(흰 바탕에 표식 하나 등)와 도장은 {@link ImageSieve}가 걸러낸다.
     */
    private static void addImage(List<ImageEntry> images, Element pic,
                                 Map<String, byte[]> binDataMap, String stem) {
        List<Element> imageEls = descendantElements(pic, "IMAGE");
        if (imageEls.isEmpty()) {
            return;
        }
        byte[] data = binDataMap.get(imageEls.get(0).getAttribute("BinItem"));
        if (data == null || data.length == 0) {
            return;
        }
        if (!ImageSieve.accept(data)) {
            return;
        }
        images.add(new ImageEntry(
                stem + "_img" + images.size() + "." + ImageFormats.extensionFor(data), data));
    }

    // ── 표 파싱 ─────────────────────────────────────────────────

    /**
     * TABLE 요소를 raw 표 모델로 변환한다. CELL의 ColAddr/RowAddr/ColSpan/RowSpan과
     * PARALIST 문단 텍스트를 읽어 cells를 채우고, 병합 범위를 grid에 복제한다.
     */
    private static RawTable parseTable(Element tbl) {
        int rowCount = parseIntAttr(tbl, "RowCount", 0);
        int colCount = parseIntAttr(tbl, "ColCount", 0);

        List<List<String>> grid = new ArrayList<>(rowCount);
        for (int r = 0; r < rowCount; r++) {
            List<String> row = new ArrayList<>(colCount);
            for (int c = 0; c < colCount; c++) {
                row.add("");
            }
            grid.add(row);
        }

        List<RawCell> cells = new ArrayList<>();
        for (Element rowEl : directChildElements(tbl, "ROW")) {
            for (Element cellEl : directChildElements(rowEl, "CELL")) {
                int colAddr = parseIntAttr(cellEl, "ColAddr", 0);
                int rowAddr = parseIntAttr(cellEl, "RowAddr", 0);
                int colSpan = Math.max(1, parseIntAttr(cellEl, "ColSpan", 1));
                int rowSpan = Math.max(1, parseIntAttr(cellEl, "RowSpan", 1));

                StringBuilder cellText = new StringBuilder();
                for (Element paralist : directChildElements(cellEl, "PARALIST")) {
                    for (Element p : directChildElements(paralist, "P")) {
                        String t = getText(p).trim();
                        if (!t.isEmpty()) {
                            if (cellText.length() > 0) {
                                cellText.append("\n");
                            }
                            cellText.append(t);
                        }
                    }
                }
                String text = cellText.toString();

                cells.add(new RawCell(rowAddr, colAddr, rowSpan, colSpan, text));
                for (int r = rowAddr; r < Math.min(rowAddr + rowSpan, rowCount); r++) {
                    for (int c = colAddr; c < Math.min(colAddr + colSpan, colCount); c++) {
                        grid.get(r).set(c, text);
                    }
                }
            }
        }
        return new RawTable(rowCount, colCount, cells, grid);
    }

    // ── XML 헬퍼 ────────────────────────────────────────────────

    /** elem 내부 모든 CHAR 텍스트를 이어붙인다. TABLE/PICTURE 내부는 건너뜀. */
    private static String getText(Element elem) {
        StringBuilder sb = new StringBuilder();
        walkText(elem, sb);
        return sb.toString();
    }

    /** CHAR 텍스트만 재귀 수집하고, 중첩 TABLE/PICTURE 하위는 건너뛴다(중복 방지). */
    private static void walkText(Node node, StringBuilder sb) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
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

    /** parent의 직계 자식 중 태그명이 일치하는 요소만 반환한다(중첩 제외). */
    private static List<Element> directChildElements(Element parent, String tag) {
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

    /** root 하위(자손 포함) 전체에서 태그명이 일치하는 요소를 반환한다. */
    private static List<Element> descendantElements(Element root, String tag) {
        List<Element> result = new ArrayList<>();
        NodeList all = root.getElementsByTagName(tag);
        for (int i = 0; i < all.getLength(); i++) {
            result.add((Element) all.item(i));
        }
        return result;
    }

    /** 정수 속성을 읽는다. 속성이 없거나 숫자가 아니면 defaultValue. */
    private static int parseIntAttr(Element elem, String attr, int defaultValue) {
        String v = elem.getAttribute(attr);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 확장자를 제외한 파일명(이미지 파일명 접두사로 사용). */
    private static String stemOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** 수집된 이미지 1건: 저장 파일명과 원본 바이트. */
    private record ImageEntry(String name, byte[] data) {
    }

    /** 단일 파싱 패스 결과: raw 문서 + 순서가 고정된 이미지 목록. */
    private record ParseResult(RawDocument raw, List<ImageEntry> images) {
    }
}
