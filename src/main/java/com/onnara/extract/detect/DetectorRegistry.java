package com.onnara.extract.detect;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 형식 키 → 스캔 판별기 매핑.
 *
 * <p>키는 {@link DocFormat#key()}가 내는 문자열이며, 파일 확장자와 같은 공간을 쓴다
 * (판정 불가일 때 확장자로 폴백하기 위해서다). 등록되지 않은 형식은 예외를 던져
 * 배치에서 [실패]로 격리된다. 새 형식을 추가할 때는 판별기를 구현해 {@link #register}로
 * 연결한다(스캔 변형이 없는 형식이면 항상 false를 반환하는 스텁으로 등록).
 */
public final class DetectorRegistry {

    /** 형식 키(소문자) → 판별기. */
    private static final Map<String, ScanDetector> DETECTORS = new LinkedHashMap<>();

    /** 기본 지원 형식 5종을 등록한다. */
    static {
        register("pdf", new PdfScanDetector());
        register("hwp", new HwpScanDetector());
        register("hwp3", new Hwp3ScanDetector());
        register("hwpx", new HwpxScanDetector());
        register("hml", new HmlScanDetector());
    }

    /** 인스턴스화 방지 — 정적 레지스트리 클래스. */
    private DetectorRegistry() {
    }

    /** 형식 키에 판별기를 등록한다(새 형식 통합 시 호출). */
    public static void register(String ext, ScanDetector detector) {
        DETECTORS.put(ext.toLowerCase(Locale.ROOT), detector);
    }

    /**
     * 파일 <b>내용</b>으로 판정한 형식에 맞는 판별기로 스캔 여부를 판정한다.
     *
     * <p>확장자가 아니라 매직바이트를 근거로 삼는다({@link DocFormat#routingKey}) —
     * HWPX를 {@code .hwp}로 저장한 파일처럼 확장자가 어긋난 문서를 엉뚱한 엔진으로
     * 보내 버리지 않기 위해서다. 판정할 수 없는 파일만 확장자로 폴백한다.
     */
    public static boolean isScanned(Path file) {
        String key = DocFormat.routingKey(file);
        ScanDetector detector = DETECTORS.get(key);
        if (detector == null) {
            throw new IllegalArgumentException("지원하지 않는 확장자입니다: " + key + " (" + file + ")");
        }
        return detector.isScanned(file);
    }

    /** 소문자 확장자 (점 제외). 확장자가 없으면 빈 문자열. */
    public static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}