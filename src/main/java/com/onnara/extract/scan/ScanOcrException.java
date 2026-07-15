package com.onnara.extract.scan;

/** OCR 프로세스 실행 실패 — CLI가 파일 단위 [실패] 격리로 처리한다. */
public class ScanOcrException extends RuntimeException {

    public ScanOcrException(String message) {
        super(message);
    }

    public ScanOcrException(String message, Throwable cause) {
        super(message, cause);
    }
}
