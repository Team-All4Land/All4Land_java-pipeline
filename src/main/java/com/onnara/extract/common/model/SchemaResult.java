package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * 표준 스키마 JSON(§5): {"source_file", "records": [...], "images": [...]}.
 *
 * <p>file_type / is_scanned / engine은 DbLoader가 documents 컬럼(§6)을 채우는 데
 * 필요해 함께 실어 나른다.
 */
@JsonPropertyOrder({"source_file", "file_type", "is_scanned", "engine", "records", "images"})
public class SchemaResult {

    private String sourceFile;
    private String fileType;
    private boolean scanned;
    private String engine;
    private List<NoticeRecord> records = new ArrayList<>();
    private List<RawImage> images = new ArrayList<>();

    public SchemaResult() {
    }

    public SchemaResult(String sourceFile, String fileType, boolean scanned, String engine) {
        this.sourceFile = sourceFile;
        this.fileType = fileType;
        this.scanned = scanned;
        this.engine = engine;
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

    @JsonProperty("engine")
    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    @JsonProperty("records")
    public List<NoticeRecord> getRecords() {
        return records;
    }

    public void setRecords(List<NoticeRecord> records) {
        this.records = records;
    }

    @JsonProperty("images")
    public List<RawImage> getImages() {
        return images;
    }

    public void setImages(List<RawImage> images) {
        this.images = images;
    }
}
