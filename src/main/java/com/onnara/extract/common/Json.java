package com.onnara.extract.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

/**
 * 파이프라인 공용 Jackson 설정의 단일 정의처.
 *
 * <p>OCR 서비스 응답에는 계약(§4) 외 필드("markdown")가 추가되므로
 * 알 수 없는 필드는 무시한다. 필드명은 각 DTO의 {@code @JsonProperty}가
 * 결정한다 — 전역 네이밍 전략은 두지 않는다(Python 산출물과 바이트 호환 유지).
 */
public final class Json {

    /** 읽기·쓰기 공용 매퍼. 알 수 없는 필드는 무시하도록 설정된다. */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 사람이 읽는 산출물(raw.json / schema.json) 저장용 들여쓰기 라이터. */
    public static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();

    /** 인스턴스화 방지 — 정적 상수만 제공하는 유틸리티 클래스. */
    private Json() {
    }
}
