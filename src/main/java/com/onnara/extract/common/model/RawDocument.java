package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * raw JSON 공통 계약(§4)의 단일 정의처 — 모든 추출 경로(네이티브 Extractor,
 * OCR 서비스 응답)가 이 형식을 출력한다. Python 버전과 필드명(snake_case)까지 동일.
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

    private String sourceFile;
    private String fileType;
    private boolean scanned;
    private List<RawContent> content = new ArrayList<>();
    private List<RawImage> images = new ArrayList<>();

    public RawDocument() {
    }

    public RawDocument(String sourceFile, String fileType, boolean scanned) {
        this.sourceFile = sourceFile;
        this.fileType = fileType;
        this.scanned = scanned;
    }

    @JsonProperty("source_file")
    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    @JsonProperty("file_type")
    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    @JsonProperty("is_scanned")
    public boolean isScanned() {
        return scanned;
    }

    public void setScanned(boolean scanned) {
        this.scanned = scanned;
    }

    @JsonProperty("content")
    public List<RawContent> getContent() {
        return content;
    }

    public void setContent(List<RawContent> content) {
        this.content = content;
    }

    @JsonProperty("images")
    public List<RawImage> getImages() {
        return images;
    }

    public void setImages(List<RawImage> images) {
        this.images = images;
    }
}