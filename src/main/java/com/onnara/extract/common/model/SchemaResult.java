package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.onnara.extract.common.AgencyRegistry;
import com.onnara.extract.common.SourceFileName;

import java.util.ArrayList;
import java.util.List;

/**
 * 표준 스키마 JSON(§5): {"file_nm", "records": [...], "images": [...]}.
 *
 * <p>file_extn / scan_yn / engn_nm은 DbLoader가 TB_ATCH_FILE 컬럼(§6)을 채우는 데
 * 필요해 함께 실어 나른다.
 *
 * <p>{@code body_chars}·{@code db_skip_reason}은 <b>적재 판단</b>을 실어 나른다. 판단을 매핑 단계에
 * 두고 결과를 스키마 JSON에 남겨야, {@code pipeline}으로 한 번에 돌리든 {@code map} → {@code load}로
 * 나눠 돌리든 같은 파일이 같은 결정을 받는다.
 */
@JsonPropertyOrder({"file_nm", "noti_sn", "atch_sn", "source_board", "noti_knd_cd",
        "file_extn", "real_extn",
        "scan_yn", "engn_nm", "body_char_cnt", "excl_rsn", "records", "images"})
public class SchemaResult {

    /** 원본 파일명 — 파일 단위 레코드 묶음의 키. */
    private String fileNm;
    /** 게시물 순번 — 파일명 앞자리. 크롤 산출물이 아니면 음수(0이면 아직 미확정). */
    private int notiSn;
    /** 첨부 순번 — 파일명 두 번째 자리. 크롤 산출물이 아니면 1. */
    private int atchSn;
    /** 수집처 — 어느 기관의 어느 게시판에서 긁어 왔는가. 폴더 규약 밖의 파일이면 null. */
    private AgencyRegistry.SourceBoard sourceBoard;
    /** 공고종류코드 — 제목으로 판정한다. 제목이 없거나 어느 종류에도 자리가 없으면 null. */
    private String notiKndCd;
    /** 형식 식별자: hwp / hwpx / hml / pdf — <b>파일명 확장자</b>가 기준이다. */
    private String fileExtn;
    /**
     * 내용으로 판정한 실제 형식: hwp / hwp3 / hwpx / hml / pdf (TB_ATCH_FILE.REAL_EXTN).
     *
     * <p>{@code fileExtn}과 다른 행이 곧 "확장자가 어긋난 파일" 목록이 된다.
     */
    private String realExtn;
    /** 스캔본 여부. */
    private boolean scanYn;
    /** 실제 사용된 추출 엔진 식별자(TB_ATCH_FILE.ENGN_NM). */
    private String engnNm;
    /** 본문 글자 수({@link com.onnara.extract.common.DocumentSize#bodyCharCnt}) — 적재 판단 근거. */
    private int bodyCharCnt;
    /** 적재를 건너뛰는 사유. null이면 적재 대상이다. */
    private String exclRsn;
    /** 한 파일에서 추출된 표준 레코드들(다건 목록이면 N개). */
    private List<NoticeRecord> records = new ArrayList<>();
    /** 파일에 딸린 이미지 메타(TB_ATCH_IMG 적재용). */
    private List<RawImage> images = new ArrayList<>();

    /** Jackson 역직렬화용 기본 생성자. */
    public SchemaResult() {
    }

    /** TB_ATCH_FILE 컬럼에 필요한 메타 4필드를 지정해 생성한다. */
    public SchemaResult(String fileNm, String fileExtn, boolean scanYn, String engnNm) {
        this.fileNm = fileNm;
        this.fileExtn = fileExtn;
        this.scanYn = scanYn;
        this.engnNm = engnNm;
    }

    /** 원본 파일명을 반환한다. */
    @JsonProperty("file_nm")
    public String getFileNm() {
        return fileNm;
    }

    /** 원본 파일명을 설정한다. */
    public void setFileNm(String fileNm) {
        this.fileNm = fileNm;
    }

    /** 게시물 순번(파일명 앞자리). */
    @JsonProperty("noti_sn")
    public int getNotiSn() {
        return notiSn;
    }

    /** 게시물 순번을 설정한다. */
    public void setNotiSn(int notiSn) {
        this.notiSn = notiSn;
    }

    /** 첨부 순번(파일명 두 번째 자리). */
    @JsonProperty("atch_sn")
    public int getAtchSn() {
        return atchSn;
    }

    /** 첨부 순번을 설정한다. */
    public void setAtchSn(int atchSn) {
        this.atchSn = atchSn;
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
        if (notiSn == 0) {
            // 구버전 스키마 JSON을 되읽는 경우 — 파일명에서 그때 확정한다
            SourceFileName.Parsed parsed = SourceFileName.parse(fileNm);
            notiSn = parsed.notiSn();
            atchSn = parsed.atchSn();
            return parsed;
        }
        return new SourceFileName.Parsed(notiSn, atchSn, notiSn > 0);
    }

    /**
     * 수집처를 반환한다(적재 전이거나 폴더 규약 밖이면 null).
     *
     * <p>{@code noti_sn}·{@code atch_sn}과 같은 이유로 스키마 JSON에 실어 나른다 —
     * 폴더는 {@code pipeline} 경로에서만 보이므로, 여기 적어 두지 않으면
     * {@code map} → {@code load}로 나눠 돌릴 때 기관이 사라진다.
     *
     * <p>{@code records[].agency}(본문에서 읽은 발령 기관, → {@code TB_ATCH_FILE.BODY_AGNCY_NM})와
     * 다른 층이다. 수집처와 발령 주체는 서로 다를 수 있다.
     */
    @JsonProperty("source_board")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public AgencyRegistry.SourceBoard getSourceBoard() {
        return sourceBoard;
    }

    /** 수집처를 설정한다. */
    public void setSourceBoard(AgencyRegistry.SourceBoard sourceBoard) {
        this.sourceBoard = sourceBoard;
    }

    /**
     * 공고종류코드를 반환한다({@code TB_ATCH_FILE.NOTI_KND_CD}). 못 가렸으면 null.
     *
     * <p>{@code noti_sn}과 같은 이유로 매핑 시점에 확정해 여기 실어 나른다 — 적재 때 다시
     * 분류하면 {@code pipeline} 경로와 {@code map} → {@code load} 경로가 갈릴 수 있다.
     */
    @JsonProperty("noti_knd_cd")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getNotiKndCd() {
        return notiKndCd;
    }

    /** 공고종류코드를 설정한다. */
    public void setNotiKndCd(String notiKndCd) {
        this.notiKndCd = notiKndCd;
    }

    /** 형식 식별자를 반환한다. */
    @JsonProperty("file_extn")
    public String getFileExtn() {
        return fileExtn;
    }

    /** 형식 식별자를 설정한다. */
    public void setFileExtn(String fileExtn) {
        this.fileExtn = fileExtn;
    }

    /** 내용으로 판정한 실제 형식을 반환한다(TB_ATCH_FILE.REAL_EXTN). 판정 전이면 null. */
    @JsonProperty("real_extn")
    public String getRealExtn() {
        return realExtn;
    }

    /** 내용으로 판정한 실제 형식을 설정한다. */
    public void setRealExtn(String realExtn) {
        this.realExtn = realExtn;
    }

    /** 스캔본 여부를 반환한다. */
    @JsonProperty("scan_yn")
    public boolean isScanYn() {
        return scanYn;
    }

    /** 스캔본 여부를 설정한다. */
    public void setScanYn(boolean scanYn) {
        this.scanYn = scanYn;
    }

    /** 추출 엔진 식별자를 반환한다. */
    @JsonProperty("engn_nm")
    public String getEngnNm() {
        return engnNm;
    }

    /** 추출 엔진 식별자를 설정한다. */
    public void setEngnNm(String engnNm) {
        this.engnNm = engnNm;
    }

    /** 본문 글자 수를 반환한다. */
    @JsonProperty("body_char_cnt")
    public int getBodyCharCnt() {
        return bodyCharCnt;
    }

    /** 본문 글자 수를 설정한다. */
    public void setBodyCharCnt(int bodyCharCnt) {
        this.bodyCharCnt = bodyCharCnt;
    }

    /** 적재를 건너뛰는 사유를 반환한다(적재 대상이면 null). */
    @JsonProperty("excl_rsn")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getExclRsn() {
        return exclRsn;
    }

    /** 적재를 건너뛰는 사유를 설정한다. */
    public void setExclRsn(String exclRsn) {
        this.exclRsn = exclRsn;
    }

    /** 적재 대상인지 — DbLoader가 이 값으로 건너뛸지 정한다(사유에서 파생되므로 직렬화하지 않는다). */
    @JsonIgnore
    public boolean isExcluded() {
        return exclRsn != null && !exclRsn.isBlank();
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
