package com.onnara.extract.detect;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 확장자 → 스캔 판별기 매핑.
 *
 * <p>등록되지 않은 확장자는 예외를 던져 배치에서 [실패]로 격리된다.
 * 새 형식을 추가할 때는 판별기를 구현해 {@link #register}로 연결한다
 * (스캔 변형이 없는 형식이면 항상 false를 반환하는 스텁으로 등록).
 */
public final class DetectorRegistry {

    private static final Map<String, ScanDetector> DETECTORS = new LinkedHashMap<>();

    static {
        register("pdf", new PdfScanDetector());
        // 이후 단계: hwp / hwpx / hml 판별기 등록
    }

    private DetectorRegistry() {
    }

    public static void register(String ext, ScanDetector detector) {
        DETECTORS.put(ext.toLowerCase(Locale.ROOT), detector);
    }

    /** 파일 확장자에 맞는 판별기로 스캔 여부를 판정한다. */
    public static boolean isScanned(Path file) {
        String ext = extensionOf(file);
        ScanDetector detector = DETECTORS.get(ext);
        if (detector == null) {
            throw new IllegalArgumentException("지원하지 않는 확장자입니다: " + ext + " (" + file + ")");
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