package com.onnara.extract.engine.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PdfBoxExtractor}의 이미지 추출 회귀 — 합성 PDF로 검증한다.
 *
 * <p>픽스처 파일 없이 테스트 안에서 PDF를 만들기 때문에, 재현 조건(띠 분할·미사용 리소스·
 * 도장·빈 이미지)을 하나씩 분리해 확인할 수 있다.
 */
class PdfBoxImageTest {

    private static final PdfBoxExtractor EXTRACTOR = new PdfBoxExtractor();

    @Test
    @DisplayName("가로 띠로 쪼개져 배치된 타일은 원래의 한 장으로 합쳐 저장한다")
    void stitchesHorizontalBandsBackIntoOneImage(@TempDir Path dir) throws Exception {
        BufferedImage original = photo(600, 300);
        Path pdf = dir.resolve("bands.pdf");

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // 원본을 3등분해 지면에서 맞닿게 배치 — 한컴 PDF 출력이 만드는 형태
                float width = 300f;
                float bandHeight = 50f;
                for (int band = 0; band < 3; band++) {
                    BufferedImage slice = original.getSubimage(0, band * 100, 600, 100);
                    PDImageXObject xobj = LosslessFactory.createFromImage(doc, copy(slice));
                    // 위에서부터 그리므로 y는 아래로 내려간다
                    float y = 600f - (band + 1) * bandHeight;
                    cs.drawImage(xobj, 100f, y, width, bandHeight);
                }
            }
            doc.save(pdf.toFile());
        }

        List<Path> saved = EXTRACTOR.saveImages(pdf, dir.resolve("images"));
        assertEquals(1, saved.size(), "띠 3장이 1장으로 합쳐져야 한다");

        BufferedImage merged = ImageIO.read(saved.get(0).toFile());
        assertEquals(600, merged.getWidth());
        assertEquals(300, merged.getHeight());
        // 위/가운데/아래가 원본과 같은 순서로 붙었는지 대표점으로 확인한다
        assertSimilar(original.getRGB(300, 50), merged.getRGB(300, 50));
        assertSimilar(original.getRGB(300, 150), merged.getRGB(300, 150));
        assertSimilar(original.getRGB(300, 250), merged.getRGB(300, 250));
    }

    @Test
    @DisplayName("나란히 놓였지만 픽셀 밀도가 다른 별개 사진 두 장은 합치지 않는다")
    void keepsAdjacentButUnrelatedPhotosSeparate(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("two-photos.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                PDImageXObject dense = LosslessFactory.createFromImage(doc, photo(600, 200));
                PDImageXObject sparse = LosslessFactory.createFromImage(doc, photo(150, 50));
                cs.drawImage(dense, 100f, 500f, 300f, 100f);
                cs.drawImage(sparse, 100f, 400f, 300f, 100f); // 맞닿아 있지만 밀도가 4배 차이
            }
            doc.save(pdf.toFile());
        }

        assertEquals(2, EXTRACTOR.saveImages(pdf, dir.resolve("images")).size());
    }

    @Test
    @DisplayName("리소스에만 있고 페이지에 그리지 않은 이미지는 저장하지 않는다")
    void ignoresImagesThatAreNeverDrawn(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("unused.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            page.setResources(new PDResources());
            PDImageXObject unused = LosslessFactory.createFromImage(doc, photo(400, 400));
            page.getResources().add(unused); // 리소스에 넣기만 하고 Do 하지 않는다
            try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                    PDPageContentStream.AppendMode.APPEND, false)) {
                PDImageXObject drawn = LosslessFactory.createFromImage(doc, photo(400, 400));
                cs.drawImage(drawn, 100f, 400f, 200f, 200f);
            }
            doc.save(pdf.toFile());
        }

        assertEquals(1, EXTRACTOR.saveImages(pdf, dir.resolve("images")).size());
    }

    @Test
    @DisplayName("흰 바탕에 점 하나뿐인 이미지와 도장은 저장하지 않는다")
    void dropsContentlessImageAndSeal(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("junk.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(LosslessFactory.createFromImage(doc, dot()), 100f, 700f, 60f, 48f);
                cs.drawImage(LosslessFactory.createFromImage(doc, seal()), 100f, 600f, 70f, 70f);
                cs.drawImage(LosslessFactory.createFromImage(doc, photo(400, 400)), 100f, 300f, 200f, 200f);
            }
            doc.save(pdf.toFile());
        }

        List<Path> saved = EXTRACTOR.saveImages(pdf, dir.resolve("images"));
        assertEquals(1, saved.size(), "본문 사진 한 장만 남아야 한다");
    }

    @Test
    @DisplayName("붉은색으로 칠해지는 스텐실 도장도 제외한다")
    void dropsStencilSealPaintedRed(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("stencil.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            // 1비트 마스크는 이미지에 색이 없다. 잉크 색은 그래픽 상태에 있으므로
            // 그대로 디코딩하면 검정으로 보여 붉은 도장을 알아볼 수 없다.
            PDImageXObject stencil = LosslessFactory.createFromImage(doc, mono(seal()));
            stencil.setStencil(true);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(new Color(200, 30, 40));
                cs.drawImage(stencil, 100f, 600f, 70f, 70f);
                cs.setNonStrokingColor(Color.BLACK);
                cs.drawImage(LosslessFactory.createFromImage(doc, photo(400, 400)), 100f, 300f, 200f, 200f);
            }
            doc.save(pdf.toFile());
        }

        assertEquals(1, EXTRACTOR.saveImages(pdf, dir.resolve("images")).size(),
                "본문 사진 한 장만 남아야 한다");
    }

    @Test
    @DisplayName("extractRaw의 images와 saveImages 결과가 순서·이름까지 일치한다")
    void extractRawAndSaveImagesAgree(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("agree.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(LosslessFactory.createFromImage(doc, dot()), 100f, 700f, 60f, 48f);
                cs.drawImage(LosslessFactory.createFromImage(doc, photo(300, 300)), 100f, 400f, 200f, 200f);
                cs.drawImage(LosslessFactory.createFromImage(doc, photo(200, 200)), 320f, 400f, 100f, 100f);
            }
            doc.save(pdf.toFile());
        }

        List<String> fromRaw = EXTRACTOR.extractRaw(pdf).getImages().stream()
                .map(image -> image.getName()).toList();
        List<String> fromSave = EXTRACTOR.saveImages(pdf, dir.resolve("images")).stream()
                .map(p -> p.getFileName().toString()).toList();

        assertEquals(fromRaw, fromSave);
        // 버려진 이미지가 번호를 먹어 구멍을 내지 않는지
        assertTrue(fromRaw.get(0).endsWith("_p1_1.png"), "실제 이름: " + fromRaw.get(0));
    }

    // ── 합성 이미지 ─────────────────────────────────────────────

    /** 결정적 난수로 채운 다색 사진(위성지도 대용). */
    private static BufferedImage photo(int w, int h) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(w * 31L + h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = 40 + random.nextInt(120);
                int g = 60 + random.nextInt(140);
                int b = 30 + random.nextInt(100);
                bi.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return bi;
    }

    /** 흰 바탕에 작은 표식 하나 — 보고된 '정보 없는 이미지'. */
    private static BufferedImage dot() {
        BufferedImage bi = new BufferedImage(200, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 160);
        g.setColor(Color.BLACK);
        g.fillRect(30, 70, 10, 10);
        g.dispose();
        return bi;
    }

    /** 붉은 관인. */
    private static BufferedImage seal() {
        BufferedImage bi = new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 240, 240);
        g.setColor(new Color(200, 30, 40));
        g.setStroke(new java.awt.BasicStroke(8f));
        g.drawOval(16, 16, 208, 208);
        g.setFont(g.getFont().deriveFont(60f));
        g.drawString("印", 90, 140);
        g.dispose();
        return bi;
    }

    /** 컬러 이미지를 1비트 흑백으로 바꾼다(스텐실 마스크 원본용). */
    private static BufferedImage mono(BufferedImage src) {
        BufferedImage out = new BufferedImage(
                src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, Color.WHITE, null);
        g.dispose();
        return out;
    }

    /** getSubimage는 원본을 공유하므로 독립 복사본을 만든다. */
    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** 병합 시 리샘플링이 들어가므로 채널당 약간의 차이를 허용한다. */
    private static void assertSimilar(int expected, int actual) {
        for (int shift : new int[] {16, 8, 0}) {
            int e = (expected >> shift) & 0xFF;
            int a = (actual >> shift) & 0xFF;
            assertTrue(Math.abs(e - a) <= 24,
                    () -> String.format("색이 다르다: %06X vs %06X", expected & 0xFFFFFF, actual & 0xFFFFFF));
        }
    }
}
