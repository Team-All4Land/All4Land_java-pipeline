package com.onnara.extract.engine.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageSieve} 판정 경계 검증.
 *
 * <p>핵심은 "정보가 없는 이미지는 버리되, 잉크가 적을 뿐인 도면은 살린다"는 두 축이
 * 서로를 침범하지 않는지 확인하는 것이다.
 */
class ImageSieveTest {

    /** 지면 크기를 모르는 경우(HWP 계열)를 나타내는 인자. */
    private static final double UNKNOWN = Double.NaN;

    /** 흰 배경의 빈 캔버스를 만든다. */
    private static BufferedImage blank(int w, int h) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return bi;
    }

    @Test
    @DisplayName("흰 바탕에 점 하나만 찍힌 이미지는 제외한다 (보고된 결함의 직접 회귀)")
    void rejectsSingleDotOnBlankCanvas() {
        BufferedImage bi = blank(200, 160);
        Graphics2D g = bi.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(30, 70, 10, 10); // 전체의 0.3%, 한구석에 몰려 있다
        g.dispose();

        assertFalse(ImageSieve.accept(bi, UNKNOWN, UNKNOWN));
    }

    @Test
    @DisplayName("잉크는 적어도 지면 전체에 퍼진 선도면은 남긴다 (오탐 회귀)")
    void keepsSparseLineDrawingSpreadAcrossThePage() {
        BufferedImage bi = blank(800, 600);
        Graphics2D g = bi.createGraphics();
        g.setColor(Color.BLACK);
        // 위치도처럼 가는 선이 사방으로 뻗은 그림 — 잉크량은 점 하나와 비슷하지만
        // 바운딩박스가 이미지 거의 전체를 덮는다
        g.drawLine(10, 10, 790, 590);
        g.drawLine(10, 590, 790, 10);
        g.drawRect(20, 20, 760, 560);
        g.setColor(new Color(60, 90, 160));
        g.drawOval(300, 200, 200, 200);
        g.dispose();

        assertTrue(ImageSieve.accept(bi, UNKNOWN, UNKNOWN));
    }

    @Test
    @DisplayName("흑백 2색 선도면을 남긴다 — 색 수로 거르면 도면이 통째로 죽는다")
    void keepsTwoColorLineDrawing() {
        BufferedImage bi = blank(1000, 700);
        Graphics2D g = bi.createGraphics();
        g.setColor(Color.BLACK);
        g.setStroke(new java.awt.BasicStroke(1f));
        for (int i = 0; i < 6; i++) {
            g.drawLine(80, 100 + i * 90, 920, 100 + i * 90); // 필지 경계선
        }
        g.drawRect(80, 100, 840, 450);
        g.dispose();

        // 흑백 도면은 색이 2개뿐이고 잉크도 1% 미만이다. 색 수를 조건에 넣으면
        // 이 부류가 통째로 걸린다 — 잉크가 어디까지 뻗었는지만 본다.
        assertTrue(ImageSieve.accept(bi, UNKNOWN, UNKNOWN));
    }

    @Test
    @DisplayName("1px 가는 선만 있는 도면을 남긴다 — 표본을 성기게 잡으면 선을 건너뛴다")
    void keepsHairlineDrawingInLargeImage() {
        BufferedImage bi = blank(1600, 1200);
        Graphics2D g = bi.createGraphics();
        g.setColor(Color.BLACK);
        g.setStroke(new java.awt.BasicStroke(1f));
        // 표본 간격의 배수를 피한 좌표에 hairline을 둔다
        g.drawLine(37, 101, 1550, 101);
        g.drawLine(37, 1099, 1550, 1099);
        g.drawLine(37, 101, 37, 1099);
        g.dispose();

        assertTrue(ImageSieve.accept(bi, UNKNOWN, UNKNOWN));
    }

    @Test
    @DisplayName("지면을 가로지르는 얇은 선을 남긴다 — 뻗은 범위는 면적이 아니라 최대 변으로 잰다")
    void keepsThinFullWidthRule() {
        BufferedImage bi = blank(1200, 300);
        Graphics2D g = bi.createGraphics();
        g.setColor(Color.BLACK);
        g.setStroke(new java.awt.BasicStroke(1f));
        g.drawLine(20, 150, 1180, 150);
        g.dispose();

        // 바운딩박스 면적으로 재면 0.3%라 한구석에 몰린 것처럼 보이지만,
        // 실제로는 가로로 이미지 전체(97%)에 걸쳐 있다.
        assertTrue(ImageSieve.accept(bi, UNKNOWN, UNKNOWN));
    }

    @Test
    @DisplayName("단색 캔버스는 제외한다")
    void rejectsUniformCanvas() {
        assertFalse(ImageSieve.accept(blank(300, 300), UNKNOWN, UNKNOWN));
    }

    @Test
    @DisplayName("괘선 조각처럼 아주 작은 이미지는 제외한다")
    void rejectsTinyImage() {
        assertFalse(ImageSieve.accept(blank(16, 4), UNKNOWN, UNKNOWN));
    }

    @Test
    @DisplayName("붉은 관인은 제외한다 — 흰 배경이 아니라 잉크 픽셀 기준으로 재기 때문")
    void rejectsRedSeal() {
        BufferedImage bi = stamp();
        assertFalse(ImageSieve.accept(bi, 25.0, 25.0));
    }

    @Test
    @DisplayName("고해상도로 스캔된 관인도 지면 크기가 작으면 제외한다")
    void rejectsHighResolutionSealByPageSize() {
        BufferedImage bi = new BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 900, 900);
        g.setColor(new Color(200, 30, 40));
        g.setStroke(new java.awt.BasicStroke(24f));
        g.drawOval(60, 60, 780, 780);
        g.setFont(g.getFont().deriveFont(220f));
        g.drawString("印", 320, 560);
        g.dispose();

        // 픽셀로는 900px이지만 지면에서는 30mm — 예전 픽셀 기준(300px)은 이걸 놓쳤다
        assertFalse(ImageSieve.accept(bi, 30.0, 30.0));
    }

    @Test
    @DisplayName("팔레트(인덱스) 컬러 관인도 제외한다 — 예전엔 컬러스페이스 검사에서 새어나갔다")
    void rejectsIndexedColorSeal() {
        BufferedImage rgb = stamp();
        BufferedImage indexed = new BufferedImage(
                rgb.getWidth(), rgb.getHeight(), BufferedImage.TYPE_BYTE_INDEXED);
        Graphics2D g = indexed.createGraphics();
        g.drawImage(rgb, 0, 0, null);
        g.dispose();

        assertFalse(ImageSieve.accept(indexed, 25.0, 25.0));
    }

    @Test
    @DisplayName("위성지도 같은 다색 사진은 남긴다")
    void keepsPhotograph() {
        BufferedImage bi = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < 300; y++) {
            for (int x = 0; x < 400; x++) {
                int r = 40 + random.nextInt(120);
                int g = 60 + random.nextInt(140);
                int b = 30 + random.nextInt(100);
                bi.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        assertTrue(ImageSieve.accept(bi, 120.0, 90.0));
    }

    @Test
    @DisplayName("붉지만 지면에서 큰 이미지는 도장이 아니다 (붉은 계열 사진 오탐 방지)")
    void keepsLargeRedImage() {
        BufferedImage bi = stamp();
        assertTrue(ImageSieve.accept(bi, 150.0, 150.0));
    }

    @Test
    @DisplayName("디코딩할 수 없는 형식은 통과시킨다 (fail-open)")
    void keepsUndecodableBytes() {
        assertTrue(ImageSieve.accept(new byte[] {0x01, 0x02, 0x03, 0x04}));
    }

    /** 흰 바탕에 붉은 원과 글자가 든 전형적인 관인 이미지. */
    private static BufferedImage stamp() {
        BufferedImage bi = blank(240, 240);
        Graphics2D g = bi.createGraphics();
        g.setColor(new Color(200, 30, 40));
        g.setStroke(new java.awt.BasicStroke(8f));
        g.drawOval(16, 16, 208, 208);
        g.setFont(g.getFont().deriveFont(60f));
        g.drawString("印", 90, 140);
        g.dispose();
        return bi;
    }
}
