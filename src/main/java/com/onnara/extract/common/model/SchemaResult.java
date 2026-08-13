package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.onnara.extract.common.SourceFileName;

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
@JsonPropertyOrder({"source_file", "notice_no", "attach_no", "file_type", "detected_format",
        "is_scanned", "engine", "body_chars", "db_skip_reason", "records", "images"})
public class SchemaResult {

    /** 원본 파일명 — 파일 단위 레코드 묶음의 키. */
    private String sourceFile;
    /** 게시물 순번 — 파일명 앞자리. 크롤 산출물이 아니면 음수(0이면 아직 미확정). */
    private int noticeNo;
    /** 첨부 순번 — 파일명 두 번째 자리. 크롤 산출물이 아니면 1. */
    private int attachNo;
    /** 형식 식별자: hwp / hwpx / hml / pdf — <b>파일명 확장자</b>가 기준이다. */
    private String fileType;
    /**
     * 내용으로 판정한 실제 형식: hwp / hwp3 / hwpx / hml / pdf (documents.detected_format).
     *
     * <p>{@code fileType}과 다른 행이 곧 "확장자가 어긋난 파일" 목록이 된다.
     */
    private String detectedFormat;
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

    /** 게시물 순번(파일명 앞자리). */
    @JsonProperty("notice_no")
    public int getNoticeNo() {
        return noticeNo;
    }

    /** 게시물 순번을 설정한다. */
    public void setNoticeNo(int noticeNo) {
        this.noticeNo = noticeNo;
    }

    /** 첨부 순번(파일명 두 번째 자리). */
    @JsonProperty("attach_no")
    public int getAttachNo() {
        return attachNo;
    }

    /** 첨부 순번을 설정한다. */
    public void setAttachNo(int attachNo) {
        this.attachNo = attachNo;
    }

    /**
     * 적재 키 — 게시물 순번과 첨부 순번.
     *
     * <p>매핑 단계에서 한 번 확정해 스키마 JSON에 실어 두므로, {@code pipeline}으로 한 번에
     * 도는 경로와 {@code map} → {@code load}로 나눠 도는 경로가 같은 순번을 받는다.
     * 폴백 순번은 호출마다 새로 발급되기 때문에, 적재 시점에 다시 파싱하면 두 경로가
     * 서로 다른 게시물로 갈린다.
     */
    @JsonIgnore
    public SourceFileName.Parsed attachmentKey() {
        if (noticeNo == 0) {
            // 구버전 스키마 JSON을 되읽는 경우 — 파일명에서 그때 확정한다
            SourceFileName.Parsed parsed = SourceFileName.parse(sourceFile);
            noticeNo = parsed.noticeNo();
            attachNo = parsed.attachNo();
            return parsed;
        }
        return new SourceFileName.Parsed(noticeNo, attachNo, noticeNo > 0);
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

    /** 내용으로 판정한 실제 형식을 반환한다(documents.detected_format). 판정 전이면 null. */
    @JsonProperty("detected_format")
    public String getDetectedFormat() {
        return detectedFormat;
    }

    /** 내용으로 판정한 실제 형식을 설정한다. */
    public void setDetectedFormat(String detectedFormat) {
        this.detectedFormat = detectedFormat;
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
