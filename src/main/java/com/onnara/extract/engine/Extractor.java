package com.onnara.extract.engine;

import com.onnara.extract.common.model.RawDocument;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 2차 분기: 확장자별 네이티브 추출 엔진 계약.
 *
 * <p>어떤 형식이든 raw JSON 계약({@link RawDocument})만 지키면
 * 매핑(common)·적재(db) 로직이 전부 재사용된다.
 */
public interface Extractor {

    /** 이 엔진이 해당 확장자(소문자, 점 제외)를 지원하는지 여부. */
    boolean supports(String ext);

    /** 파일에서 원시 콘텐츠(문단/표/이미지 메타)를 추출한다. */
    RawDocument extractRaw(Path file) throws IOException;

    /** 내장 이미지를 outDir에 저장하고 저장 경로 목록을 반환한다. */
    List<Path> saveImages(Path file, Path outDir) throws IOException;
}