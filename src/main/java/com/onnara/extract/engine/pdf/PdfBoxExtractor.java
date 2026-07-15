package com.onnara.extract.engine.pdf;

import com.onnara.extract.common.model.RawContent;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.engine.Extractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Apache PDFBox 기반 PDF 원문 추출 — Python pdf/pdfplumber/reader.py 포팅.
 *
 * <p>문서 순서를 유지하며 본문 문단, 표(셀 그리드), 내장 이미지를
 * 다른 엔진들과 동일한 raw 계약({@link RawDocument})으로 추출한다.
 * 표 탐지(선분 클러스터링)는 {@link TableDetector}가 담당한다.
 *
 * <p>도장(관인) 이미지는 요구사항상 추출 대상이 아니므로
 * '소형 + 붉은색 우세' 휴리스틱으로 걸러낸다.
 */
public class PdfBoxExtractor implements Extractor {

    /**
     * 도장 휴리스틱: 최대 변이 이 값(px) 이하이면서 붉은색 우세(R-max(G,B))가
     * STAMP_RED_DOMINANCE 이상인 이미지는 도장으로 간주.
     */
    private static final int STAMP_MAX_DIM = 300;
    private static final int STAMP_RED_DOMINANCE = 25;

    /** {@code pdf} 확장자만 지원한다. */
    @Override
    public boolean supports(String ext) {
        return "pdf".equals(ext);
    }

    /** 엔진 식별자 "pdfbox". */
    @Override
    public String engineName() {
        return "pdfbox";
    }

    /**
     * 페이지마다 표(TableDetector)와 표 밖 문단을 각각 뽑아 top 좌표로 정렬해
     * content에 담고, 도장을 제외한 내장 이미지를 메타로 추가한다.
     */
    @Override
    public RawDocument extractRaw(Path file) throws IOException {
        RawDocument raw = new RawDocument(file.getFileName().toString(), "pdf", false);

        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            int pageNo = 0;
            for (PDPage page : doc.getPages()) {
                pageNo++;

                List<TableDetector.DetectedTable> tables = TableDetector.detect(page);
                List<Item> items = new ArrayList<>();
                for (TableDetector.DetectedTable t : tables) {
                    items.add(new Item(t.getTop(), t.getTable()));
                }
                for (Line line : pageParagraphs(doc, pageNo, tables)) {
                    items.add(new Item(line.top, new RawParagraph(line.text)));
                }

                if (items.isEmpty()) {
                    System.err.println(
                            "[경고] " + file.getFileName() + " p." + pageNo
                                    + ": 텍스트가 없는 페이지입니다. 스캔본이라면 OCR 파이프라인 대상입니다.");
                    continue;
                }
                // 문단·표 혼재 순서 유지: top 좌표 기준 안정 정렬
                items.sort(null);
                for (Item item : items) {
                    raw.getContent().add(item.content);
                }
            }

            for (ImageEntry img : iterImages(doc, stemOf(file))) {
                raw.getImages().add(new RawImage(img.name, img.data.length));
            }
        }
        return raw;
    }

    /** 도장을 제외한 내장 이미지를 outDir에 쓰고 저장 경로 목록을 반환한다. */
    @Override
    public List<Path> saveImages(Path file, Path outDir) throws IOException {
        List<Path> saved = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            for (ImageEntry img : iterImages(doc, stemOf(file))) {
                Files.createDirectories(outDir);
                Path dest = outDir.resolve(img.name);
                Files.write(dest, img.data);
                saved.add(dest);
            }
        }
        return saved;
    }

    /** 확장자를 제외한 파일명(이미지 파일명 접두사로 사용). */
    private static String stemOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    // ------------------------------------------------------------------
    // 문단 추출 (_page_paragraphs 포팅)
    // ------------------------------------------------------------------

    /** (top, content) 정렬용 항목. 정렬은 top 기준(동률이면 삽입 순서 유지). */
    private static final class Item implements Comparable<Item> {
        final double top;
        final RawContent content;

        Item(double top, RawContent content) {
            this.top = top;
            this.content = content;
        }

        /** top 좌표 오름차순 정렬(위→아래). 동률은 삽입 순서 유지. */
        @Override
        public int compareTo(Item o) {
            return Double.compare(top, o.top);
        }
    }

    /** 텍스트 한 줄과 그 세로 위치(top/bottom) — 표 영역 판정·문단 병합에 사용. */
    private static final class Line {
        String text;
        double top;
        double bottom;

        Line(String text, double top, double bottom) {
            this.text = text;
            this.top = top;
            this.bottom = bottom;
        }
    }

    /**
     * 표 영역 밖의 텍스트를 (y좌표, 문단) 목록으로 추출한다.
     *
     * <p>'('로 시작하는 줄은 직전 문단의 연속으로 본다
     * (예: 기간의 '(변경) ...' 줄, 제목의 '(※...)' 줄).
     */
    private static List<Line> pageParagraphs(
            PDDocument doc, int pageNo, List<TableDetector.DetectedTable> tables) throws IOException {
        LineCollector collector = new LineCollector();
        collector.setStartPage(pageNo);
        collector.setEndPage(pageNo);
        collector.getText(doc); // 출력 문자열은 버리고 줄 목록만 사용

        List<Line> paragraphs = new ArrayList<>();
        for (Line line : collector.lines) {
            String text = line.text.trim();
            if (text.isEmpty()) {
                continue;
            }
            double center = (line.top + line.bottom) / 2;
            boolean inTable = false;
            for (TableDetector.DetectedTable t : tables) {
                if (t.getTop() <= center && center <= t.getBottom()) {
                    inTable = true;
                    break;
                }
            }
            if (inTable) {
                continue;
            }
            if (!paragraphs.isEmpty() && (text.startsWith("(") || text.startsWith("（"))) {
                Line prev = paragraphs.get(paragraphs.size() - 1);
                prev.text = prev.text + "\n" + text;
            } else {
                paragraphs.add(new Line(text, line.top, line.bottom));
            }
        }
        return paragraphs;
    }

    /** 페이지의 텍스트를 줄 단위(텍스트 + top/bottom 좌표)로 수집한다. */
    private static final class LineCollector extends PDFTextStripper {

        final List<Line> lines = new ArrayList<>();

        private final StringBuilder currentText = new StringBuilder();
        private double currentTop = Double.POSITIVE_INFINITY;
        private double currentBottom = Double.NEGATIVE_INFINITY;

        /** 좌표순 정렬을 켜 줄 순서를 시각적 위→아래로 맞춘다. */
        LineCollector() throws IOException {
            setSortByPosition(true);
        }

        /** 조각 텍스트를 현재 줄에 이어 붙이고 top/bottom 경계를 갱신한다. */
        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            currentText.append(text);
            for (TextPosition tp : textPositions) {
                double bottom = tp.getYDirAdj();
                currentTop = Math.min(currentTop, bottom - tp.getHeightDir());
                currentBottom = Math.max(currentBottom, bottom);
            }
        }

        /** 단어 구분자를 현재 줄에 넣는다. */
        @Override
        protected void writeWordSeparator() {
            currentText.append(getWordSeparator());
        }

        /** 줄 구분자를 만나면 현재 줄을 확정한다. */
        @Override
        protected void writeLineSeparator() {
            flushLine();
        }

        /** 페이지 끝에서 남은 줄을 확정한 뒤 상위 처리를 이어간다. */
        @Override
        protected void endPage(PDPage page) throws IOException {
            flushLine();
            super.endPage(page);
        }

        /** 누적된 현재 줄을 lines에 추가하고 버퍼·경계를 초기화한다. */
        private void flushLine() {
            String text = currentText.toString().trim();
            if (!text.isEmpty()) {
                lines.add(new Line(text, currentTop, currentBottom));
            }
            currentText.setLength(0);
            currentTop = Double.POSITIVE_INFINITY;
            currentBottom = Double.NEGATIVE_INFINITY;
        }
    }

    // ------------------------------------------------------------------
    // 이미지 추출 (_iter_images / _is_stamp 포팅)
    // ------------------------------------------------------------------

    /** 수집된 이미지 1건: 저장 파일명과 인코딩된 바이트. */
    private static final class ImageEntry {
        final String name;
        final byte[] data;

        ImageEntry(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    /** 도장을 제외한 내장 이미지를 (이름, 바이트)로 수집한다. */
    private static List<ImageEntry> iterImages(PDDocument doc, String stem) throws IOException {
        List<ImageEntry> out = new ArrayList<>();
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int pageNo = 0;
        for (PDPage page : doc.getPages()) {
            pageNo++;
            int idx = 0;
            for (PDImageXObject image : imagesOf(page.getResources(), seen)) {
                idx++;
                BufferedImage bi = image.getImage();
                if (isStamp(image, bi)) {
                    continue;
                }
                String ext = imageExtension(image);
                byte[] data = encodeImage(image, bi, ext);
                out.add(new ImageEntry(stem + "_p" + pageNo + "_" + idx + "." + ext, data));
            }
        }
        return out;
    }

    /** 리소스(중첩 폼 XObject 포함)의 이미지들을 문서 전체 중복 제거하며 수집한다. */
    private static List<PDImageXObject> imagesOf(PDResources resources, Set<COSBase> seen)
            throws IOException {
        List<PDImageXObject> images = new ArrayList<>();
        if (resources == null) {
            return images;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobj = resources.getXObject(name);
            if (xobj instanceof PDImageXObject) {
                if (seen.add(xobj.getCOSObject())) {
                    images.add((PDImageXObject) xobj);
                }
            } else if (xobj instanceof PDFormXObject) {
                if (seen.add(xobj.getCOSObject())) {
                    images.addAll(imagesOf(((PDFormXObject) xobj).getResources(), seen));
                }
            }
        }
        return images;
    }

    /** 도장(관인) 이미지 판별: 소형이면서 붉은색이 우세하면 도장으로 본다. */
    static boolean isStamp(PDImageXObject image, BufferedImage bi) throws IOException {
        if (Math.max(bi.getWidth(), bi.getHeight()) > STAMP_MAX_DIM) {
            return false;
        }
        if (image.getColorSpace() == null || image.getColorSpace().getNumberOfComponents() < 3) {
            return false;
        }
        int w = bi.getWidth();
        int h = bi.getHeight();
        int nPx = w * h;
        int step = Math.max(1, nPx / 1000); // 표본만 확인
        long r = 0;
        long g = 0;
        long b = 0;
        long count = 0;
        for (int i = 0; i < nPx; i += step) {
            int rgb = bi.getRGB(i % w, i / w);
            r += (rgb >> 16) & 0xFF;
            g += (rgb >> 8) & 0xFF;
            b += rgb & 0xFF;
            count++;
        }
        return (double) (r - Math.max(g, b)) / count >= STAMP_RED_DOMINANCE;
    }

    /** PDFBox suffix를 저장 확장자로 정규화한다(jpg→jpeg, 기록 불가 형식은 png). */
    private static String imageExtension(PDImageXObject image) {
        String suffix = image.getSuffix();
        if (suffix == null) {
            return "png";
        }
        if ("jpg".equals(suffix)) {
            return "jpeg"; // Python(PyMuPDF) 산출물과 파일명 호환
        }
        // ImageIO로 기록할 수 없는 형식(jpx/jb2/tiff 등)은 PNG로 재인코딩
        if ("png".equals(suffix) || "gif".equals(suffix) || "bmp".equals(suffix)) {
            return suffix;
        }
        return "png";
    }

    /** 원본 스트림을 우선 사용하고(JPEG), 그 외에는 디코딩 결과를 재인코딩한다. */
    private static byte[] encodeImage(PDImageXObject image, BufferedImage bi, String ext)
            throws IOException {
        if ("jpeg".equals(ext) && COSName.DCT_DECODE.equals(firstFilter(image))) {
            // DCTDecode 단일 필터면 원본 JPEG 바이트를 그대로 사용 (PyMuPDF와 동일)
            try (InputStream in = image.getCOSObject().createRawInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        }
        BufferedImage toWrite = bi;
        if ("jpeg".equals(ext) && bi.getColorModel().hasAlpha()) {
            toWrite = stripAlpha(bi);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!ImageIO.write(toWrite, "jpeg".equals(ext) ? "jpg" : ext, bos)) {
            bos.reset();
            ImageIO.write(toWrite, "png", bos);
        }
        return bos.toByteArray();
    }

    /** 이미지의 단일 필터명을 반환한다(필터 배열이면 null) — 원본 JPEG 재사용 판단용. */
    private static COSName firstFilter(PDImageXObject image) {
        COSBase filters = image.getCOSObject().getDictionaryObject(COSName.FILTER);
        if (filters instanceof COSName) {
            return (COSName) filters;
        }
        return null;
    }

    /** 알파 채널을 흰 배경에 합성해 제거한다(JPEG는 투명도를 지원하지 않으므로). */
    private static BufferedImage stripAlpha(BufferedImage src) {
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(src, 0, 0, java.awt.Color.WHITE, null);
        return rgb;
    }
}