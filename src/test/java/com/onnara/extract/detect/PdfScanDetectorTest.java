package com.onnara.extract.detect;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 스캔 판별 회귀 테스트 — "첫 페이지 텍스트 0글자 AND 첫 페이지 이미지 ≥1" 규칙.
 *
 * <p>samples/에 PDF 픽스처가 없어 PDFBox로 각 케이스를 직접 만든다. 텍스트는 표준 14 폰트로
 * 그리므로 ASCII만 쓴다(판정은 글자 수만 보기 때문에 한글일 필요가 없다).
 */
class PdfScanDetectorTest {

    /** 케이스별 PDF를 쓰는 임시 폴더. */
    @TempDir
    Path tempDir;

    /** 판별기 — 상태가 없어 케이스마다 공유해도 된다. */
    private final PdfScanDetector detector = new PdfScanDetector();

    /** 첫 페이지에 본문이 있으면 뒤에 백지가 붙어 있어도 네이티브다. */
    @Test
    void firstPageWithTextIsNative() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            drawText(doc, addPage(doc), "Notice body text");
            addPage(doc);
            assertFalse(detector.isScanned(save(doc, "text-then-blank.pdf")));
        }
    }

    /**
     * 한 글자 + 이미지여도 네이티브 — 텍스트로 뽑히는 게 있으면 그 페이지는 스캔 이미지가 아니다.
     *
     * <p>임계치가 1이라는 것과, 텍스트 판정이 이미지 유무보다 먼저라는 것을 함께 고정한다.
     */
    @Test
    void oneCharacterOnFirstPageIsEnoughToBeNative() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = addPage(doc);
            drawText(doc, page, "A");
            drawImage(doc, page);
            assertFalse(detector.isScanned(save(doc, "one-char-with-image.pdf")));
        }
    }

    /** 첫 페이지가 이미지뿐이면 스캔본이다. */
    @Test
    void imageOnlyFirstPageIsScanned() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            drawImage(doc, addPage(doc));
            assertTrue(detector.isScanned(save(doc, "image-only.pdf")));
        }
    }

    /** 텍스트도 이미지도 없는 백지 표지는 빈 문서이지 스캔본이 아니다 — OCR로 얻을 게 없다. */
    @Test
    void blankFirstPageWithoutImageIsNotScanned() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            addPage(doc);
            assertFalse(detector.isScanned(save(doc, "blank.pdf")));
        }
    }

    /** 첫 페이지만 본다 — 뒷장에 텍스트가 있어도 스캔 판정을 뒤집지 않는다. */
    @Test
    void laterPageTextDoesNotOverrideScannedFirstPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            drawImage(doc, addPage(doc));
            drawText(doc, addPage(doc), "Attachment detail");
            assertTrue(detector.isScanned(save(doc, "scan-then-text.pdf")));
        }
    }

    /** 스캔 이미지가 폼 XObject로 한 겹 감싸여 있어도 찾아낸다(리소스 재귀 탐색). */
    @Test
    void imageNestedInFormXObjectIsScanned() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = addPage(doc);

            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 144, 144));
            PDResources formResources = new PDResources();
            formResources.add(newImage(doc));
            form.setResources(formResources);

            PDResources pageResources = new PDResources();
            pageResources.add(form);
            page.setResources(pageResources);

            assertTrue(detector.isScanned(save(doc, "nested-form-image.pdf")));
        }
    }

    /** DetectorRegistry가 .pdf를 이 판별기로 라우팅하는지 — 확장자 등록 회귀. */
    @Test
    void registryRoutesPdfToThisDetector() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            drawImage(doc, addPage(doc));
            assertTrue(DetectorRegistry.isScanned(save(doc, "routed-scan.pdf")));
        }
        try (PDDocument doc = new PDDocument()) {
            drawText(doc, addPage(doc), "Notice body text");
            assertFalse(DetectorRegistry.isScanned(save(doc, "routed-native.pdf")));
        }
    }

    /** 페이지가 없는 PDF는 판정 대상이 아니다(false). */
    @Test
    void emptyDocumentIsNotScanned() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            assertFalse(detector.isScanned(save(doc, "no-pages.pdf")));
        }
    }

    /** 문서에 A4 페이지를 붙이고 반환한다. */
    private static PDPage addPage(PDDocument doc) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        return page;
    }

    /** 페이지에 텍스트를 그린다(기존 내용을 지우지 않도록 APPEND). */
    private static void drawText(PDDocument doc, PDPage page, String text) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page, AppendMode.APPEND, true)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(72, 720);
            cs.showText(text);
            cs.endText();
        }
    }

    /** 페이지에 이미지를 그려 페이지 리소스에 PDImageXObject를 남긴다. */
    private static void drawImage(PDDocument doc, PDPage page) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page, AppendMode.APPEND, true)) {
            cs.drawImage(newImage(doc), 72, 72, 144, 144);
        }
    }

    /** 판정에 쓰이는 건 이미지의 존재뿐이라 내용은 아무래도 좋다. */
    private static PDImageXObject newImage(PDDocument doc) throws IOException {
        return LosslessFactory.createFromImage(doc, new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
    }

    /** 문서를 임시 폴더에 저장하고 경로를 반환한다. */
    private Path save(PDDocument doc, String name) throws IOException {
        Path path = tempDir.resolve(name);
        doc.save(path.toFile());
        return path;
    }
}
