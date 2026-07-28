package com.onnara.extract.engine.image;

import java.awt.image.BufferedImage;

/**
 * 이미지 한 장의 '내용이 얼마나 있는가'를 한 번의 표본 순회로 측정한 결과.
 *
 * <p>저장 여부 판정({@link ImageSieve})이 쓰는 근거 수치를 모아 둔다.
 * 배경색을 흰색으로 고정하지 않고 <b>최빈색</b>으로 잡기 때문에 검은 배경·색지
 * 스캔본도 같은 규칙으로 다룰 수 있다.
 */
public record ImageContent(
        /** 배경색과 다른(=잉크) 픽셀의 비율 0.0~1.0. */
        double inkRatio,
        /** 잉크 픽셀 바운딩박스 면적 / 전체 면적 0.0~1.0. 잉크가 없으면 0. */
        double inkBoxRatio,
        /** 잉크 픽셀 중 붉은 픽셀의 비율 0.0~1.0. 잉크가 없으면 0. */
        double redInkRatio,
        /** 4bit/채널로 양자화했을 때 서로 다른 색의 수(0~{@value #COLOR_CAP}). */
        int distinctColors,
        /** 원본 픽셀 폭. */
        int width,
        /** 원본 픽셀 높이. */
        int height) {

    /** 표본으로 볼 픽셀 수의 상한 — 두 축 stride로 이 정도까지만 훑는다. */
    private static final int MAX_SAMPLES = 20_000;

    /** 4bit×3채널 양자화 색 공간의 크기 — 히스토그램 길이이자 색 수의 상한. */
    static final int COLOR_CAP = 4096;

    /** 배경과 같은 색으로 볼 채널별 최대 허용 차. */
    private static final int BACKGROUND_TOLERANCE = 40;

    /** 붉은 픽셀 판정: R이 G·B보다 이만큼 우세해야 한다. */
    private static final int RED_DOMINANCE = 45;

    /** 붉은 픽셀 판정: R 자체가 이 값 이상이어야 한다(어두운 갈색 제외). */
    private static final int RED_MIN = 90;

    /**
     * 이미지를 표본 순회해 정보량 지표를 계산한다.
     *
     * <p>알파가 있는 픽셀은 흰 배경에 합성한 색으로 본다 — PDF·HWP 어느 쪽이든
     * 최종적으로 흰 지면 위에 놓이기 때문이다.
     */
    public static ImageContent measure(BufferedImage bi) {
        int w = bi.getWidth();
        int h = bi.getHeight();
        if (w <= 0 || h <= 0) {
            return new ImageContent(0, 0, 0, 0, Math.max(w, 0), Math.max(h, 0));
        }

        int step = 1;
        while ((long) (w / step) * (h / step) > MAX_SAMPLES) {
            step++;
        }

        // 1차 순회: 양자화 색 히스토그램 → 최빈색(배경)과 색 수
        int[] histogram = new int[COLOR_CAP];
        int distinct = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int q = quantize(composite(bi.getRGB(x, y)));
                if (histogram[q]++ == 0) {
                    distinct++;
                }
            }
        }
        int background = 0;
        for (int q = 1; q < COLOR_CAP; q++) {
            if (histogram[q] > histogram[background]) {
                background = q;
            }
        }
        int bgR = ((background >> 8) & 0xF) * 17;
        int bgG = ((background >> 4) & 0xF) * 17;
        int bgB = (background & 0xF) * 17;

        // 2차 순회: 배경 대비 잉크 픽셀 수·바운딩박스·붉은 잉크 수
        long sampled = 0;
        long ink = 0;
        long redInk = 0;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                sampled++;
                int rgb = composite(bi.getRGB(x, y));
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int delta = Math.max(Math.abs(r - bgR), Math.max(Math.abs(g - bgG), Math.abs(b - bgB)));
                if (delta <= BACKGROUND_TOLERANCE) {
                    continue;
                }
                ink++;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                if (r >= RED_MIN && r - Math.max(g, b) >= RED_DOMINANCE) {
                    redInk++;
                }
            }
        }

        if (ink == 0) {
            return new ImageContent(0, 0, 0, distinct, w, h);
        }
        double boxRatio = ((double) (maxX - minX + step) / w) * ((double) (maxY - minY + step) / h);
        return new ImageContent(
                (double) ink / sampled,
                Math.min(1.0, boxRatio),
                (double) redInk / ink,
                distinct,
                w, h);
    }

    /** 알파를 흰 배경에 합성해 불투명 RGB로 만든다. */
    private static int composite(int argb) {
        int a = (argb >>> 24) & 0xFF;
        if (a == 0xFF) {
            return argb & 0xFFFFFF;
        }
        int r = ((argb >> 16) & 0xFF) * a / 255 + (255 - a);
        int g = ((argb >> 8) & 0xFF) * a / 255 + (255 - a);
        int b = (argb & 0xFF) * a / 255 + (255 - a);
        return (r << 16) | (g << 8) | b;
    }

    /** RGB를 4bit/채널(12bit)로 양자화한다. */
    private static int quantize(int rgb) {
        return (((rgb >> 20) & 0xF) << 8) | (((rgb >> 12) & 0xF) << 4) | ((rgb >> 4) & 0xF);
    }

    /** 로그용 요약 — 제외 사유와 함께 임계값 조정 근거로 남긴다. */
    public String describe() {
        return String.format(
                "%dx%d, ink=%.2f%%, box=%.1f%%, red=%.0f%%, colors=%d",
                width, height, inkRatio * 100, inkBoxRatio * 100, redInkRatio * 100, distinctColors);
    }
}
