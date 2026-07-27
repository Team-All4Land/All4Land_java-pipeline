package com.onnara.extract.scan;

import com.onnara.extract.common.AppProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * PaddleOCR-VL CLI 실행 설정 — §7. OS 환경변수 &gt; {@code .env} &gt; application.properties 순으로 읽는다.
 *
 * <ul>
 *   <li>{@code ocr.cli.command} / {@code OCR_CLI_COMMAND}: 인터프리터/실행 파일 (예: python3, venv의 python 절대경로)</li>
 *   <li>{@code ocr.cli.script} / {@code OCR_CLI_SCRIPT}: 실행할 스크립트 경로</li>
 *   <li>{@code ocr.cli.timeout-sec} / {@code OCR_CLI_TIMEOUT_SEC}: 파일당 제한 시간 — VLM 추론은 수 분 걸릴 수 있다</li>
 *   <li>{@code ocr.images.enabled} / {@code OCR_IMAGES_ENABLED}: 네이티브 문서에 삽입된 이미지도 OCR할지</li>
 *   <li>{@code ocr.images.min-dimension} / {@code OCR_IMAGES_MIN_DIMENSION}: OCR 후보 최소 긴 변(px)</li>
 * </ul>
 *
 * @param imageOcrEnabled 네이티브 문서의 임베디드 이미지도 OCR해 raw JSON에 병합할지 여부
 * @param imageMinDimension 이 픽셀 수보다 긴 변이 짧은 이미지는 OCR하지 않는다(도장·로고·서명 제외)
 */
public record ScanOcrConfig(String command, Path script, Duration timeout,
                            boolean imageOcrEnabled, int imageMinDimension) {

    /**
     * 이미지 OCR 후보의 기본 최소 긴 변(px).
     *
     * <p>관인·로고·서명 스캔은 대개 한 변이 300px 안쪽이라 이 선 아래로 떨어지고,
     * 본문이 담긴 위치도·현장사진·표 캡처는 넉넉히 넘어간다.
     */
    public static final int DEFAULT_IMAGE_MIN_DIMENSION = 400;

    /** 이미지 OCR 설정을 기본값(켜짐)으로 두고 실행 설정만 지정한다 — 테스트·호출부 편의용. */
    public ScanOcrConfig(String command, Path script, Duration timeout) {
        this(command, script, timeout, true, DEFAULT_IMAGE_MIN_DIMENSION);
    }

    /** {@code ocr.*} 키(환경변수/.env/application.properties)에서 실행 설정을 읽어 생성한다. */
    public static ScanOcrConfig fromProperties(AppProperties props) {
        String command = props.getWithEnv("ocr.cli.command", "python3", "OCR_CLI_COMMAND");
        Path script = Path.of(
                props.getWithEnv("ocr.cli.script", "ocr-cli/paddleocr_vl_cli.py", "OCR_CLI_SCRIPT"));
        int timeoutSec = Integer.parseInt(
                props.getWithEnv("ocr.cli.timeout-sec", "300", "OCR_CLI_TIMEOUT_SEC"));
        boolean imageOcr = Boolean.parseBoolean(
                props.getWithEnv("ocr.images.enabled", "true", "OCR_IMAGES_ENABLED"));
        int minDimension = Integer.parseInt(props.getWithEnv("ocr.images.min-dimension",
                String.valueOf(DEFAULT_IMAGE_MIN_DIMENSION), "OCR_IMAGES_MIN_DIMENSION"));
        return new ScanOcrConfig(command, script, Duration.ofSeconds(timeoutSec), imageOcr, minDimension);
    }

    /** 이미지 OCR을 끈 사본을 만든다({@code --no-image-ocr} 옵션용). */
    public ScanOcrConfig withoutImageOcr() {
        return new ScanOcrConfig(command, script, timeout, false, imageMinDimension);
    }
}
