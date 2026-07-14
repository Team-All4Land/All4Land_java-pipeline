package com.onnara.extract.detect;

import java.nio.file.Path;

/**
 * 1차 분기: 스캔본 판별 계약.
 *
 * <p>스캔본으로 판별되면 확장자와 무관하게 OCR 서비스(ScanOcrClient) 경로로,
 * 네이티브면 확장자별 Extractor로 라우팅된다.
 */
public interface ScanDetector {

    /** 파일이 스캔본(이미지 전용)인지 판정한다. */
    boolean isScanned(Path file);
}