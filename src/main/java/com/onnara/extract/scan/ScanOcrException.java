package com.onnara.extract.scan;

/** OCR 프로세스 실행 실패 — CLI가 파일 단위 [실패] 격리로 처리한다. */
public class ScanOcrException extends RuntimeException {

    /** 메시지만으로 생성한다(원인 없음). */
    public ScanOcrException(String message) {
        super(message);
    }

    /** 메시지와 원인 예외로 생성한다. */
    public ScanOcrException(String message, Throwable cause) {
        super(message, cause);
    }
}
