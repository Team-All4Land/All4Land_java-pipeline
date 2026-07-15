package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * raw JSON 계약의 이미지 메타데이터:
 * {"name", "path", "size", "ocr_text"}.
 *
 * <ul>
 *   <li>{@code path}: 저장 경로 — DB 적재(ref_files)에 필요. saveImages 실행 시 채워진다.</li>
 *   <li>{@code ocr_text}: 선택 필드 — 현재 Java 파이프라인은 채우지 않지만
 *       raw JSON 계약(Python 산출물 호환) 유지를 위해 남겨둔다.</li>
 * </ul>
 */
@JsonPropertyOrder({"name", "path", "size", "ocr_text"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RawImage {

    private String name;
    private String path;
    private long size;
    private String ocrText;

    public RawImage() {
    }

    public RawImage(String name, long size) {
        this.name = name;
        this.size = size;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @JsonProperty("path")
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    @JsonProperty("size")
    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    @JsonProperty("ocr_text")
    public String getOcrText() {
        return ocrText;
    }

    public void setOcrText(String ocrText) {
        this.ocrText = ocrText;
    }
}