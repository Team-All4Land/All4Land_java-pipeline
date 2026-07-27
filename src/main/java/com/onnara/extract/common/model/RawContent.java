package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    /** 이 항목을 OCR로 읽어 낸 원본 이미지 파일명. 문서 본문에서 직접 뽑은 항목은 null. */
    private String sourceImage;

    /** content 항목 구분자: "paragraph" 또는 "table". */
    @JsonProperty("type")
    public abstract String getType();

    /**
     * 이 항목의 출처 이미지 파일명(없으면 null — 문서 본문에서 직접 추출된 항목).
     *
     * <p>사진·위치도처럼 본문이 이미지 안에 들어 있는 문서는 OCR로 읽은 내용이
     * 본문 항목과 한 목록에 섞이는데, 어느 이미지에서 나왔는지 남겨 두어야
     * "네이티브 본문"과 "이미지에서 읽은 것"을 되짚어 구분할 수 있다.
     */
    @JsonProperty("source_image")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getSourceImage() {
        return sourceImage;
    }

    /** 출처 이미지 파일명을 설정한다(OCR 병합 시 채워진다). */
    public void setSourceImage(String sourceImage) {
        this.sourceImage = sourceImage;
    }
}