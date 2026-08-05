package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * 표준 스키마 JSON(§5): {"source_file", "records": [...], "images": [...]}.
 *
 * <p>file_type / is_scanned / engine은 DbLoader가 documents 컬럼(§6)을 채우는 데
 * 필요해 함께 실어 나른다.
 *
 * <p>{@code body_chars}·{@code db_skip_reason}은 <b>적재 판단</b>을 실어 나른다. 판단을 매핑 단계에
 * 두고 결과를 스키마 JSON에 남겨야, {@code pipeline}으로 한 번에 돌리든 {@code map} → {@code load}로
 * 나눠 돌리든 같은 파일이 같은 결정을 받는다.
 */
@JsonPropertyOrder({"source_file", "file_type", "is_scanned", "engine",
        "body_chars", "db_skip_reason", "records", "images"})
public class SchemaResult {

    /** 원본 파일명 — 파일 단위 레코드 묶음의 키. */
    private String sourceFile;
    /** 형식 식별자: hwp / hwpx / hml / pdf. */
    private String fileType;
    /** 스캔본 여부. */
    private boolean scanned;
    /** 실제 사용된 추출 엔진 식별자(documents.engine). */
    private String engine;
    /** 본문 글자 수({@link com.onnara.extract.common.DocumentSize#bodyChars}) — 적재 판단 근거. */
    private int bodyChars;
    /** 적재를 건너뛰는 사유. null이면 적재 대상이다. */
    private String dbSkipReason;
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

    /** 본문 글자 수를 반환한다. */
    @JsonProperty("body_chars")
    public int getBodyChars() {
        return bodyChars;
    }

    /** 본문 글자 수를 설정한다. */
    public void setBodyChars(int bodyChars) {
        this.bodyChars = bodyChars;
    }

    /** 적재를 건너뛰는 사유를 반환한다(적재 대상이면 null). */
    @JsonProperty("db_skip_reason")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDbSkipReason() {
        return dbSkipReason;
    }

    /** 적재를 건너뛰는 사유를 설정한다. */
    public void setDbSkipReason(String dbSkipReason) {
        this.dbSkipReason = dbSkipReason;
    }

    /** 적재 대상인지 — DbLoader가 이 값으로 건너뛸지 정한다(사유에서 파생되므로 직렬화하지 않는다). */
    @JsonIgnore
    public boolean isDbSkipped() {
        return dbSkipReason != null && !dbSkipReason.isBlank();
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
