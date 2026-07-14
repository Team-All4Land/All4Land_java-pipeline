package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * raw JSON 계약의 content 항목 공통 타입 (문단·표 혼재, 문서 등장 순서 유지).
 *
 * <p>Python 버전과 동일하게 {@code "type"} 필드("paragraph"/"table")로 구분한다.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = RawParagraph.class, name = "paragraph"),
        @JsonSubTypes.Type(value = RawTable.class, name = "table"),
})
public abstract class RawContent {

    /** content 항목 구분자: "paragraph" 또는 "table". */
    @JsonProperty("type")
    public abstract String getType();
}