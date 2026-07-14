package com.onnara.extract;

/**
 * 이미지 바이너리를 받아 OCR 텍스트를 반환하는 계약.
 * 이 프로젝트는 구현체를 제공하지 않는다 - 다른 담당자가 쓰는 OCR 방식
 * (Tesseract든, 사내 OCR API든, PaddleOCR이든)으로 이 인터페이스만 구현해서
 * RunPipeline/DbLoader에 꽂으면 된다.
 *
 * 텍스트가 없거나 인식 실패 시 null 또는 빈 문자열을 반환하면 된다
 * (DbLoader가 그 경우 ocr_text 컬럼에 NULL로 저장한다).
 */
public interface OcrProvider {
    String recognize(byte[] imageData);
}