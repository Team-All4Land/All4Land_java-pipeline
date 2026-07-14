package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** raw JSON 계약의 문단 항목: {"type": "paragraph", "text": "..."}. */
@JsonPropertyOrder({"type", "text"})
public class RawParagraph extends RawContent {

    private String text;

    public RawParagraph() {
    }

    public RawParagraph(String text) {
        this.text = text;
    }

    @Override
    public String getType() {
        return "paragraph";
    }

    @JsonProperty("text")
    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}