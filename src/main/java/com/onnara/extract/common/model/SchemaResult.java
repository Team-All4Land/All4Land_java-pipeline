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

    /** 원본 파일명 — 파일 단위 레코드 묶음의 키. */
    private String sourceFile;
    /** 형식 식별자: hwp / hwpx / hml / pdf. */
    private String fileType;
    /** 스캔본 여부. */
    private boolean scanned;
    /** 실제 사용된 추출 엔진 식별자(documents.engine). */
    private String engine;
    /** 한 파일에서 추출된 표준 레코드들(다건 목록이면 N개). */
    private List<NoticeRecord> records = new ArrayList<>();
    /** 파일에 딸린 이미지 메타(ref_files 적재용). */
    private List<RawImage> images = new ArrayList<>();

    /** Jackson 역직렬화용 기본 생성자. */
    public SchemaResult() {
    }

    /** documents 컬럼에 필요한 메타 4필드를 지정해 생성한다. */
    public SchemaResult(String sourceFile, String fileType, boolean scanned, String engine) {
        this.sourceFile = sourceFile;
        this.fileType = fileType;
        this.scanned = scanned;
        this.engine = engine;
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

    /** 형식 식별자를 반환한다. */
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

    /** 스캔본 여부를 설정한다. */
    public void setScanned(boolean scanned) {
        this.scanned = scanned;
    }

    /** 추출 엔진 식별자를 반환한다. */
    @JsonProperty("engine")
    public String getEngine() {
        return engine;
    }

    /** 추출 엔진 식별자를 설정한다. */
    public void setEngine(String engine) {
        this.engine = engine;
    }

    /** 표준 레코드 목록을 반환한다. */
    @JsonProperty("records")
    public List<NoticeRecord> getRecords() {
        return records;
    }

    /** 표준 레코드 목록을 설정한다. */
    public void setRecords(List<NoticeRecord> records) {
        this.records = records;
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
