package com.onnara.extract.scan;

import com.onnara.extract.common.AppProperties;

import java.time.Duration;

/**
 * OCR 서비스(ocr-service) 접속 설정 — §7.
 *
 * <p>우선순위: CLI {@code --ocr-url} &gt; application.properties.
 */
public record ScanOcrConfig(String baseUrl, Duration timeout, int retries) {

    public static ScanOcrConfig fromProperties(AppProperties props, String cliUrlOverride) {
        String url = cliUrlOverride != null && !cliUrlOverride.isBlank()
                ? cliUrlOverride
                : props.get("ocr.service.url", "http://127.0.0.1:8000");
        int timeoutSec = Integer.parseInt(props.get("ocr.service.timeout-sec", "300"));
        int retries = Integer.parseInt(props.get("ocr.service.retries", "2"));
        return new ScanOcrConfig(url, Duration.ofSeconds(timeoutSec), retries);
    }
}
