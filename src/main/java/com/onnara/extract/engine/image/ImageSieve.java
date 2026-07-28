package com.onnara.extract.engine.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 추출한 이미지를 첨부로 저장할지 판정한다 — 전 엔진(PDF/HWP/HWPX/HML) 공용.
 *
 * <p>두 가지를 걸러낸다.
 *
 * <ol>
 *   <li><b>정보가 없는 이미지</b> — 흰 바탕에 표식 하나만 찍힌 앵커·크롭마크 따위.
 *       본문과 무관하므로 애초에 추출 대상이 아니다. 같은 이미지가 여러 첨부로
 *       반복되던 현상도 이것을 뽑지 않으면 함께 사라진다.</li>
 *   <li><b>도장(관인)</b> — 요구사항상 추출 대상이 아니다.</li>
 * </ol>
 *
 * <p>임계값은 전부 이 클래스 상단에 모아 둔다. 실제 배치 파일로 조정할 때
 * 여기만 보면 되도록 한 것이다 — 제외 로그에 측정값({@link ImageContent#describe()})이
 * 함께 찍히므로 조정 근거를 로그에서 얻을 수 있다.
 */
public final class ImageSieve {

    // ── 정보량 임계값 ──────────────────────────────────────────

    /** '점 하나' 판정: 잉크가 이 비율 미만이면서 한구석에 몰려 있으면 버린다. */
    private static final double SPARSE_INK_RATIO = 0.01;

    /** '점 하나' 판정: 잉크 바운딩박스가 지면의 이 비율 미만이면 한구석에 몰린 것으로 본다. */
    private static final double SPARSE_INK_BOX_RATIO = 0.25;

    /** '2색 표식' 판정: 색이 이 수 이하이면서 잉크가 {@link #FLAT_INK_RATIO} 미만이면 버린다. */
    private static final int FLAT_COLOR_COUNT = 4;

    /** '2색 표식' 판정의 잉크 비율 상한. */
    private static final double FLAT_INK_RATIO = 0.02;

    /** 이 픽셀 크기(최대 변) 미만은 불릿·괘선 조각으로 보고 버린다. */
    private static final int MIN_PIXELS = 24;

    /** 지면 크기를 아는 경우, 최대 변이 이 mm 미만이면 버린다. */
    private static final double MIN_MM = 3.0;

    // ── 도장 임계값 ────────────────────────────────────────────

    /** 도장 크기 상한(지면 기준, mm). 관인은 이보다 크지 않다. */
    private static final double STAMP_MAX_MM = 45.0;

    /** 지면 크기를 모를 때 쓰는 도장 크기 상한(픽셀). */
    private static final int STAMP_MAX_PIXELS = 600;

    /** 도장 종횡비 하한(정사각·원형에 가까워야 한다). */
    private static final double STAMP_MIN_ASPECT = 0.6;

    /** 도장 종횡비 상한. */
    private static final double STAMP_MAX_ASPECT = 1.7;

    /** 도장 잉크 비율 하한 — 이보다 옅으면 도장이라 보기 어렵다. */
    private static final double STAMP_MIN_INK = 0.005;

    /** 도장 잉크 비율 상한 — 이보다 빽빽하면 사진에 가깝다. */
    private static final double STAMP_MAX_INK = 0.6;

    /** 잉크 픽셀 중 붉은 픽셀이 이 비율 이상이어야 도장으로 본다. */
    private static final double STAMP_MIN_RED_INK = 0.55;

    /** 인스턴스화 방지 — 정적 판정 함수만 제공하는 유틸리티 클래스. */
    private ImageSieve() {
    }

    /**
     * HWP/HWPX/HML의 BinData처럼 <b>지면 크기를 모르는</b> 바이트 이미지를 판정한다.
     * 판정은 픽셀 기준으로 대체되며, 디코딩할 수 없는 형식(wmf/emf/ole 등)은 통과시킨다.
     */
    public static String reject(byte[] data) {
        return reject(decode(data), Double.NaN, Double.NaN);
    }

    /** 바이트를 이미지로 디코딩한다. ImageIO가 읽지 못하면 {@code null}. */
    private static BufferedImage decode(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try (InputStream in = new ByteArrayInputStream(data)) {
            return ImageIO.read(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 저장 대상이면 {@code null}, 버릴 대상이면 사람이 읽을 수 있는 제외 사유를 반환한다.
     *
     * @param decoded    디코딩된 이미지. ImageIO가 읽지 못한 형식(wmf/emf 등)이면 {@code null}을
     *                   넘겨도 되며, 그 경우 판정하지 않고 통과시킨다(fail-open).
     * @param widthMm    지면에 그려지는 폭(mm). 모르면 {@link Double#NaN}.
     * @param heightMm   지면에 그려지는 높이(mm). 모르면 {@link Double#NaN}.
     */
    public static String reject(BufferedImage decoded, double widthMm, double heightMm) {
        if (decoded == null) {
            return null; // 판정할 수 없으면 살린다 — 본문 이미지를 잃는 쪽이 더 나쁘다
        }
        ImageContent c = ImageContent.measure(decoded);

        boolean knowsPageSize = !Double.isNaN(widthMm) && !Double.isNaN(heightMm)
                && widthMm > 0 && heightMm > 0;

        if (Math.max(c.width(), c.height()) < MIN_PIXELS) {
            return "극소 이미지 (" + c.describe() + ")";
        }
        if (knowsPageSize && Math.max(widthMm, heightMm) < MIN_MM) {
            return String.format("극소 이미지 (%.1fx%.1fmm)", widthMm, heightMm);
        }
        if (c.inkRatio() == 0) {
            return "단색 이미지 (" + c.describe() + ")";
        }
        // 잉크가 적으면서 '동시에' 한구석에 몰려 있을 때만 버린다.
        // 선이 드문 도면·위치도는 잉크가 지면 전체에 퍼져 바운딩박스가 크므로 살아남는다.
        if (c.inkRatio() < SPARSE_INK_RATIO && c.inkBoxRatio() < SPARSE_INK_BOX_RATIO) {
            return "점 하나 (" + c.describe() + ")";
        }
        if (c.distinctColors() <= FLAT_COLOR_COUNT && c.inkRatio() < FLAT_INK_RATIO) {
            return "표식 (" + c.describe() + ")";
        }
        if (isStamp(c, knowsPageSize, widthMm, heightMm)) {
            return "도장 (" + c.describe() + ")";
        }
        return null;
    }

    /**
     * 도장(관인) 판별.
     *
     * <p>붉은색 우세를 <b>잉크 픽셀 대비</b>로 재는 것이 핵심이다. 도장은 흰 바탕에 가는
     * 붉은 선이라, 흰 배경을 분모에 넣으면 아무리 새빨간 도장도 평균값이 임계에 못 미친다.
     * 크기도 픽셀이 아니라 지면 기준(mm)으로 본다 — 고해상도로 스캔된 도장은 픽셀만 보면
     * 얼마든지 커질 수 있기 때문이다.
     */
    private static boolean isStamp(ImageContent c, boolean knowsPageSize,
                                   double widthMm, double heightMm) {
        if (knowsPageSize) {
            if (Math.max(widthMm, heightMm) > STAMP_MAX_MM) {
                return false;
            }
        } else if (Math.max(c.width(), c.height()) > STAMP_MAX_PIXELS) {
            return false;
        }
        double aspect = (double) c.width() / c.height();
        if (aspect < STAMP_MIN_ASPECT || aspect > STAMP_MAX_ASPECT) {
            return false;
        }
        if (c.inkRatio() < STAMP_MIN_INK || c.inkRatio() > STAMP_MAX_INK) {
            return false;
        }
        return c.redInkRatio() >= STAMP_MIN_RED_INK;
    }
}
