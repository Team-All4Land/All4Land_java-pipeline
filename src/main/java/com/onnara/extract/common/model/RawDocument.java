package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * raw JSON 공통 계약(§4)의 단일 정의처 — 모든 추출 경로(네이티브 Extractor,
 * OCR CLI 결과)가 이 형식을 출력한다. Python 버전과 필드명(snake_case)까지 동일.
 *
 * <pre>
 * {
 *   "source_file": "원본파일명.ext",
 *   "file_type": "pdf",
 *   "is_scanned": false,
 *   "content": [ {"type": "paragraph", ...}, {"type": "table", ...} ],
 *   "images": [ {"name": ..., "path": ..., "size": ...} ]
 * }
 * </pre>
 */
@JsonPropertyOrder({"source_file", "file_type", "is_scanned", "content", "images"})
public class RawDocument {

    /** 원본 파일명(경로 제외). */
    private String sourceFile;
    /** 형식 식별자: hwp / hwpx / hml / pdf. */
    private String fileType;
    /** 스캔본 여부 — OCR 경로로 처리됐으면 true. */
    private boolean scanned;
    /** 문단·표가 문서 등장 순서로 섞여 담기는 본문 항목 목록. */
    private List<RawContent> content = new ArrayList<>();
    /** 추출된 이미지 메타 목록(저장 경로·크기). */
    private List<RawImage> images = new ArrayList<>();

    /** Jackson 역직렬화용 기본 생성자. */
    public RawDocument() {
    }

    /** 메타 3필드를 지정해 생성한다(content·images는 이후 채운다). */
    public RawDocument(String sourceFile, String fileType, boolean scanned) {
        this.sourceFile = sourceFile;
        this.fileType = fileType;
        this.scanned = scanned;
    }

    /** 원본 파일명을 반환한다. */
    @JsonProperty("source_file")
    public String getSourceFile() {
        return sourceFile;
    }

    /** 원본 파일명을 설정한다. */
    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    /** 형식 식별자(hwp/hwpx/hml/pdf)를 반환한다. */
    @JsonProperty("file_type")
    public String getFileType() {
        return fileType;
    }

    /** 형식 식별자를 설정한다. */
    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    /** 스캔본 여부를 반환한다. */
    @JsonProperty("is_scanned")
    public boolean isScanned() {
        return scanned;
    }

    /** 스캔본 여부를 설정한다(OCR 경로가 true로 강제). */
    public void setScanned(boolean scanned) {
        this.scanned = scanned;
    }

    /** 본문 항목(문단·표) 목록을 반환한다. */
    @JsonProperty("content")
    public List<RawContent> getContent() {
        return content;
    }

    /** 본문 항목 목록을 설정한다. */
    public void setContent(List<RawContent> content) {
        this.content = content;
    }

    /** 이미지 메타 목록을 반환한다. */
    @JsonProperty("images")
    public List<RawImage> getImages() {
        return images;
    }

    /** 이미지 메타 목록을 설정한다. */
    public void setImages(List<RawImage> images) {
        this.images = images;
    }
}
