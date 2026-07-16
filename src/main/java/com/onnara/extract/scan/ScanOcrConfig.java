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
 * </ul>
 */
public record ScanOcrConfig(String command, Path script, Duration timeout) {

    /** {@code ocr.cli.*} 키(환경변수/.env/application.properties)에서 실행 설정을 읽어 생성한다. */
    public static ScanOcrConfig fromProperties(AppProperties props) {
        String command = props.getWithEnv("ocr.cli.command", "python3", "OCR_CLI_COMMAND");
        Path script = Path.of(
                props.getWithEnv("ocr.cli.script", "ocr-cli/paddleocr_vl_cli.py", "OCR_CLI_SCRIPT"));
        int timeoutSec = Integer.parseInt(
                props.getWithEnv("ocr.cli.timeout-sec", "300", "OCR_CLI_TIMEOUT_SEC"));
        return new ScanOcrConfig(command, script, Duration.ofSeconds(timeoutSec));
    }
}
