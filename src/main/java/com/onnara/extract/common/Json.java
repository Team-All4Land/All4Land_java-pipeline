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

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();

    private Json() {
    }
}
