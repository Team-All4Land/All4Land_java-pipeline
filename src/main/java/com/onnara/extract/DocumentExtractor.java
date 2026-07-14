package com.onnara.extract;

import com.onnara.extract.model.ExtractedDocument;

/**
 * 파일 하나를 파싱해서 공통 모델(ExtractedDocument)로 반환하는 계약.
 * hwp/hwpx/hml 파서(HwpExtractor/HwpxExtractor/HwpmlExtractor)가 이 인터페이스를
 * 구현하고, PDF 등 다른 포맷 담당자도 동일하게 구현하면 RunPipeline이나
 * 저장 로직(DbLoader)을 포맷과 무관하게 공유할 수 있다.
 */
public interface DocumentExtractor {
    ExtractedDocument parse(String path) throws Exception;
}
