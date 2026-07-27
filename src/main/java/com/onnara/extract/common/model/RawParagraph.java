package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** raw JSON 계약의 문단 항목: {"type": "paragraph", "text": "...", "source_image": "..."}. */
@JsonPropertyOrder({"type", "text", "source_image"})
public class RawParagraph extends RawContent {

    /** 문단 텍스트. */
    private String text;

    /** Jackson 역직렬화용 기본 생성자. */
    public RawParagraph() {
    }

    /** 텍스트를 지정해 문단을 생성한다. */
    public RawParagraph(String text) {
        this.text = text;
    }

    /** content 항목 구분자 — 항상 "paragraph". */
    @Override
    public String getType() {
        return "paragraph";
    }

    /** 문단 텍스트를 반환한다. */
    @JsonProperty("text")
    public String getText() {
        return text;
    }

    /** 문단 텍스트를 설정한다. */
    public void setText(String text) {
        this.text = text;
    }
}
