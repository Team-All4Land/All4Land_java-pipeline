package com.onnara.extract.engine.pdf;

import com.onnara.extract.common.Heuristics;
import com.onnara.extract.common.model.RawContent;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;
import com.onnara.extract.engine.Extractor;
import com.onnara.extract.engine.image.ImageSieve;
import com.onnara.extract.engine.image.PdfImageTiles;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Paint;
import java.awt.geom.Point2D;
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
import java.util.Map;
import java.util.Set;

/**
 * Apache PDFBox 기반 PDF 원문 추출 — Python pdf/pdfplumber/reader.py 포팅.
 *
 * <p>문서 순서를 유지하며 본문 문단, 표(셀 그리드), 내장 이미지를
 * 다른 엔진들과 동일한 raw 계약({@link RawDocument})으로 추출한다.
 * 표 탐지(선분 클러스터링)는 {@link TableDetector}가 담당한다.
 *
 * <p>이미지는 리소스 딕셔너리가 아니라 <b>콘텐츠 스트림</b>에서 모은다. 페이지에 실제로
 * 그려진 것만 대상으로 삼고, 배치 좌표를 알 수 있어 {@link PdfImageTiles}가 가로 띠로
 * 쪼개진 타일을 원래의 한 장으로 되돌릴 수 있다. 저장 여부(정보가 없는 이미지·도장 제외)는
 * 전 엔진 공용 {@link ImageSieve}가 판정한다.
 */
public class PdfBoxExtractor implements Extractor {

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
     * content에 담고, 저장 대상으로 판정된 이미지를 메타로 추가한다.
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
                attachCaptions(items);
                for (Item item : items) {
                    raw.getContent().add(item.content);
                }
            }

            for (ImageEntry img : iterImages(doc, stemOf(file), file.getFileName().toString())) {
                raw.getImages().add(new RawImage(img.name, img.data.length));
            }
        }
        return raw;
    }

    /**
     * 한 페이지의 표에 캡션을 붙인다 — PDF에는 캡션이라는 구조가 없어 <b>추정</b>이다.
     *
     * <p>PDF는 표도 선분 뭉치에서 복원한 것이고 캡션도 그냥 문단이라, 다른 형식처럼
     * "이 표의 캡션"이라고 적힌 자리가 없다. 대신 표 바로 앞뒤 문단 중 캡션 형태
     * ("&lt;표 1&gt;", "[그림 2]" 등)인 것을 고른다 —
     * {@link Heuristics#nearestCaption}이 이 용도로 만들어져 있다.
     *
     * <p>표 자리에는 빈 문자열을 넣어 색인을 맞춘다. 빈 문자열은 캡션 패턴에 걸리지 않으므로
     * 표가 연달아 나와도 앞 표를 캡션으로 집어 가지 않는다.
     *
     * <p>추정이므로 PDF에만 적용한다. HWP·HWPX·HML은 캡션이 컨트롤에 직접 달려 있어
     * 굳이 추측할 이유가 없다.
     */
    private static void attachCaptions(List<Item> items) {
        List<String> texts = new ArrayList<>(items.size());
        for (Item item : items) {
            texts.add(item.content instanceof RawParagraph p ? p.getText() : "");
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).content instanceof RawTable table) {
                table.setCaption(Heuristics.nearestCaption(texts, i));
            }
        }
    }

    /** 저장 대상으로 판정된 이미지를 outDir에 쓰고 저장 경로 목록을 반환한다. */
    @Override
    public List<Path> saveImages(Path file, Path outDir) throws IOException {
        List<Path> saved = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            for (ImageEntry img : iterImages(doc, stemOf(file), file.getFileName().toString())) {
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

    /**
     * 페이지에 그려진 이미지를 모아 타일 병합 → 저장 판정을 거친 뒤 (이름, 바이트)로 반환한다.
     *
     * <p>{@code extractRaw}와 {@code saveImages}가 같은 결과를 내야
     * {@code PipelineSupport.bindImagePaths}의 순서·이름 일치 계약이 유지되므로
     * 두 경로가 이 메서드를 공유한다.
     */
    private static List<ImageEntry> iterImages(PDDocument doc, String stem, String sourceName)
            throws IOException {
        List<ImageEntry> out = new ArrayList<>();
        // 여러 페이지에 반복 등장하는 같은 스트림(머리말 로고 등)은 한 번만 저장한다
        Set<COSBase> emitted = Collections.newSetFromMap(new IdentityHashMap<>());
        int pageNo = 0;
        for (PDPage page : doc.getPages()) {
            pageNo++;
            DrawnImages drawn = new DrawnImages(page, sourceName, pageNo);
            drawn.processPage(page);

            int idx = 0;
            for (PdfImageTiles.Merged merged : PdfImageTiles.merge(drawn.placements)) {
                if (!merged.isStitched()
                        && !emitted.add(merged.single().image().getCOSObject())) {
                    continue; // 앞 페이지에서 이미 저장한 스트림
                }
                if (!ImageSieve.accept(merged.stitched(), merged.widthMm(), merged.heightMm())) {
                    continue;
                }
                idx++;
                String ext = merged.isStitched() ? "png" : imageExtension(merged.single().image());
                byte[] data = merged.isStitched()
                        ? encodePng(merged.stitched())
                        : encodeImage(merged.single().image(), merged.stitched(), ext);
                out.add(new ImageEntry(stem + "_p" + pageNo + "_" + idx + "." + ext, data));
            }
        }
        return out;
    }

    /**
     * 콘텐츠 스트림을 훑어 <b>실제로 그려진</b> 이미지를 배치 사각형과 함께 모은다.
     *
     * <p>리소스 딕셔너리만 보던 예전 방식은 (1) 페이지에 그려지지 않는 이미지까지 저장하고
     * (2) 배치를 몰라 가로 띠로 쪼개진 타일을 되붙일 수 없었다.
     *
     * <p>{@link PDFGraphicsStreamEngine}을 상속하는 이유는 이 클래스가 {@code q}/{@code Q}/
     * {@code cm}/{@code Do} 연산자 처리를 등록해 주기 때문이다. 맨 {@code PDFStreamEngine}은
     * 연산자를 하나도 등록하지 않아 변환 행렬이 항등으로 남는다. 그리기 계열 콜백은
     * 이미지 수집에 쓰이지 않으므로 전부 빈 구현이다.
     */
    private static final class DrawnImages extends PDFGraphicsStreamEngine {

        final List<PdfImageTiles.Placement> placements = new ArrayList<>();

        private final Map<COSBase, BufferedImage> decoded = new IdentityHashMap<>();
        private final String sourceName;
        private final int pageNo;

        DrawnImages(PDPage page, String sourceName, int pageNo) {
            super(page);
            this.sourceName = sourceName;
            this.pageNo = pageNo;
        }

        /** 이미지가 그려질 때마다 현재 변환 행렬로 배치를 기록한다. */
        @Override
        public void drawImage(PDImage image) {
            if (image instanceof PDImageXObject xobject) {
                record(xobject);
            }
        }

        /** 현재 변환 행렬로 배치 사각형을 계산해 목록에 담는다. */
        private void record(PDImageXObject image) {
            BufferedImage bi = decode(image);
            if (bi == null || bi.getWidth() <= 0 || bi.getHeight() <= 0) {
                return;
            }
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            float scaleX = ctm.getScaleX();
            float scaleY = ctm.getScaleY();
            boolean skewed = Math.abs(ctm.getShearX()) > 1e-4f
                    || Math.abs(ctm.getShearY()) > 1e-4f
                    || scaleX < 0 || scaleY < 0;

            // 회전·기울임이 있으면 축 정렬 크기를 알 수 없으므로 배율 크기로 근사한다
            float width = skewed ? ctm.getScalingFactorX() : scaleX;
            float height = skewed ? ctm.getScalingFactorY() : scaleY;
            if (width <= 0 || height <= 0) {
                return;
            }
            // 음수 배율(뒤집힘)이면 translate가 좌하단이 아니므로 사각형을 정규화한다
            float x = scaleX < 0 ? ctm.getTranslateX() - width : ctm.getTranslateX();
            float y = scaleY < 0 ? ctm.getTranslateY() - height : ctm.getTranslateY();
            placements.add(new PdfImageTiles.Placement(image, bi, x, y, width, height, skewed));
        }

        /**
         * 같은 스트림을 여러 번 그려도 디코딩은 한 번만 한다.
         *
         * <p>스텐실 마스크(ImageMask)는 잉크 색이 이미지가 아니라 그래픽 상태에 있다.
         * 그대로 디코딩하면 검정으로 나와 붉은 도장을 알아볼 수 없으므로, 현재
         * 비스트로크 색으로 칠해 실제로 지면에 찍히는 모습으로 만든다. 같은 스텐실을
         * 다른 색으로 그릴 수 있어 이 경우는 캐시하지 않는다.
         */
        private BufferedImage decode(PDImageXObject image) {
            if (image.isStencil()) {
                try {
                    return image.getStencilImage(nonStrokingPaint());
                } catch (IOException | RuntimeException e) {
                    return null;
                }
            }
            COSBase key = image.getCOSObject();
            if (decoded.containsKey(key)) {
                return decoded.get(key);
            }
            BufferedImage bi;
            try {
                bi = image.getImage();
            } catch (IOException | RuntimeException e) {
                // 디코딩 불가 이미지 하나 때문에 파일 전체를 실패시키지 않는다.
                // 다만 조용히 버리면 본문 이미지가 사라진 것을 알 수 없으므로 경고는 남긴다.
                System.err.println("[경고] " + sourceName + " p." + pageNo
                        + ": 이미지를 디코딩할 수 없어 건너뜁니다 (" + e.getMessage() + ")");
                bi = null;
            }
            decoded.put(key, bi);
            return bi;
        }

        /** 스텐실을 칠할 현재 비스트로크 색. 알 수 없으면 검정. */
        private Paint nonStrokingPaint() {
            try {
                PDColor color = getGraphicsState().getNonStrokingColor();
                float[] rgb = color.getColorSpace().toRGB(color.getComponents());
                return new Color(clamp(rgb[0]), clamp(rgb[1]), clamp(rgb[2]));
            } catch (IOException | RuntimeException e) {
                return Color.BLACK;
            }
        }

        /** 색 성분을 0.0~1.0 범위로 자른다(변환 결과가 범위를 벗어날 수 있다). */
        private static float clamp(float value) {
            return Math.max(0f, Math.min(1f, value));
        }

        // ── 그리기 콜백: 이미지 수집과 무관하므로 전부 빈 구현 ──────────

        /** 경로·도형은 수집 대상이 아니다. */
        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        }

        /** 클리핑은 수집 대상이 아니다. */
        @Override
        public void clip(int windingRule) {
        }

        /** 경로 이동은 수집 대상이 아니다. */
        @Override
        public void moveTo(float x, float y) {
        }

        /** 직선 경로는 수집 대상이 아니다. */
        @Override
        public void lineTo(float x, float y) {
        }

        /** 곡선 경로는 수집 대상이 아니다. */
        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        }

        /** 경로 상태를 추적하지 않으므로 현재 점은 원점으로 고정한다. */
        @Override
        public Point2D getCurrentPoint() {
            return new Point2D.Float(0, 0);
        }

        /** 경로 닫기는 수집 대상이 아니다. */
        @Override
        public void closePath() {
        }

        /** 경로 종료는 수집 대상이 아니다. */
        @Override
        public void endPath() {
        }

        /** 선 그리기는 수집 대상이 아니다. */
        @Override
        public void strokePath() {
        }

        /** 면 채우기는 수집 대상이 아니다. */
        @Override
        public void fillPath(int windingRule) {
        }

        /** 채우고 그리기는 수집 대상이 아니다. */
        @Override
        public void fillAndStrokePath(int windingRule) {
        }

        /** 셰이딩은 수집 대상이 아니다. */
        @Override
        public void shadingFill(COSName shadingName) {
        }
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

    /** 병합된 이미지는 원본 스트림이 없으므로 무손실 PNG로 인코딩한다. */
    private static byte[] encodePng(BufferedImage bi) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", bos);
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