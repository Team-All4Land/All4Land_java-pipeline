package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * raw JSON 계약의 이미지 메타데이터:
 * {"name", "path", "size", "caption", "ocr_text"}.
 *
 * <ul>
 *   <li>{@code path}: 저장 절대경로 — DB 적재(OS_ATCH_IMG_DTL)에 필요. saveImages 실행 시 채워진다.</li>
 *   <li>{@code caption}: 그림에 달린 설명("[그림 2] 위치도" 등). 없으면 키가 빠진다.</li>
 *   <li>{@code ocr_text}: 선택 필드 — 현재 Java 파이프라인은 채우지 않지만
 *       raw JSON 계약(Python 산출물 호환) 유지를 위해 남겨둔다.</li>
 * </ul>
 */
@JsonPropertyOrder({"name", "path", "size", "caption", "ocr_text"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RawImage {

    /** 저장 파일명(매직바이트로 판별한 확장자 포함). */
    private String name;
    /** 저장된 이미지의 절대 경로. saveImages 시 채워진다. */
    private String path;
    /** 이미지 바이트 크기. */
    private long size;
    /** 그림에 달린 캡션(없으면 null). */
    private String caption;
    /** OCR 텍스트(선택) — 계약 호환용, 현재 미사용. */
    private String ocrText;

    /** Jackson 역직렬화용 기본 생성자. */
    public RawImage() {
    }

    /** 파일명·크기를 지정해 생성한다(path·ocrText는 이후 채운다). */
    public RawImage(String name, long size) {
        this.name = name;
        this.size = size;
    }

    /** 저장 파일명을 반환한다. */
    @JsonProperty("name")
    public String getName() {
        return name;
    }

    /** 저장 파일명을 설정한다. */
    public void setName(String name) {
        this.name = name;
    }

    /** 절대 저장 경로를 반환한다. */
    @JsonProperty("path")
    public String getPath() {
        return path;
    }

    /** 절대 저장 경로를 설정한다. */
    public void setPath(String path) {
        this.path = path;
    }

    /** 바이트 크기를 반환한다. */
    @JsonProperty("size")
    public long getSize() {
        return size;
    }

    /** 바이트 크기를 설정한다. */
    public void setSize(long size) {
        this.size = size;
    }

    /** 그림 캡션을 반환한다(없으면 null). */
    @JsonProperty("caption")
    public String getCaption() {
        return caption;
    }

    /** 그림 캡션을 설정한다. 빈 문자열은 null로 눕혀 JSON에서 빠지게 한다. */
    public void setCaption(String caption) {
        this.caption = caption == null || caption.isBlank() ? null : caption.trim();
    }

    /** OCR 텍스트를 반환한다(현재 항상 null). */
    @JsonProperty("ocr_text")
    public String getOcrText() {
        return ocrText;
    }

    /** OCR 텍스트를 설정한다. */
    public void setOcrText(String ocrText) {
        this.ocrText = ocrText;
    }
}
