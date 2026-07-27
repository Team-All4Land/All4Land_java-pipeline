package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * raw JSON 계약의 이미지 메타데이터:
 * {"name", "path", "size", "ocr_text"}.
 *
 * <ul>
 *   <li>{@code path}: 저장 절대경로 — DB 적재(ref_files)에 필요. saveImages 실행 시 채워진다.</li>
 *   <li>{@code ocr_text}: 이미지에서 읽어 낸 평문. 본문이 사진 안에 있는 문서(붙임 현장사진,
 *       위치도)의 내용을 이미지별로 남긴다 — {@code ImageOcrEnricher}가 채우고
 *       {@code ref_files.ocr_text}로 적재된다. 읽지 않았거나 글자가 없으면 null.</li>
 * </ul>
 */
@JsonPropertyOrder({"name", "path", "size", "ocr_text"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RawImage {

    /** 저장 파일명(매직바이트로 판별한 확장자 포함). */
    private String name;
    /** 저장된 이미지의 절대 경로. saveImages 시 채워진다. */
    private String path;
    /** 이미지 바이트 크기. */
    private long size;
    /** 이미지에서 OCR로 읽은 평문(선택) — 읽지 않았거나 글자가 없으면 null. */
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

    /** 이미지에서 읽은 OCR 평문을 반환한다(없으면 null). */
    @JsonProperty("ocr_text")
    public String getOcrText() {
        return ocrText;
    }

    /** OCR 텍스트를 설정한다. */
    public void setOcrText(String ocrText) {
        this.ocrText = ocrText;
    }
}
