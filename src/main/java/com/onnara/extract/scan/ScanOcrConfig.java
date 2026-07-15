package com.onnara.extract.scan;

import com.onnara.extract.common.AppProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * PaddleOCR-VL CLI 실행 설정 — §7. application.properties에서만 읽는다.
 *
 * <ul>
 *   <li>{@code ocr.cli.command}: 인터프리터/실행 파일 (예: python3, venv의 python 절대경로)</li>
 *   <li>{@code ocr.cli.script}: 실행할 스크립트 경로</li>
 *   <li>{@code ocr.cli.timeout-sec}: 파일당 제한 시간 — VLM 추론은 수 분 걸릴 수 있다</li>
 * </ul>
 */
public record ScanOcrConfig(String command, Path script, Duration timeout) {

    /** application.properties의 {@code ocr.cli.*} 키에서 실행 설정을 읽어 생성한다. */
    public static ScanOcrConfig fromProperties(AppProperties props) {
        String command = props.get("ocr.cli.command", "python3");
        Path script = Path.of(props.get("ocr.cli.script", "ocr-cli/paddleocr_vl_cli.py"));
        int timeoutSec = Integer.parseInt(props.get("ocr.cli.timeout-sec", "300"));
        return new ScanOcrConfig(command, script, Duration.ofSeconds(timeoutSec));
    }
}
