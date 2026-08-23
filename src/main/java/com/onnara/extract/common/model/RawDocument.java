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
 *   "atch_file_nm": "원본파일명.ext",
 *   "file_extn_nm": "pdf",
 *   "actl_file_extn_nm": "pdf",
 *   "scan_yn": false,
 *   "content": [ {"type": "paragraph", ...}, {"type": "table", ...} ],
 *   "images": [ {"name": ..., "path": ..., "size": ...} ]
 * }
 * </pre>
 */
@JsonPropertyOrder({"atch_file_nm", "file_extn_nm", "actl_file_extn_nm", "scan_yn", "content", "images"})
public class RawDocument {

    /** 원본 파일명(경로 제외). */
    private String atchFileNm;
    /** 형식 식별자: hwp / hwpx / hml / pdf — <b>파일명 확장자</b>가 기준이다. */
    private String fileExtnNm;
    /**
     * 파일 내용(매직바이트)으로 판정한 실제 형식: hwp / hwp3 / hwpx / hml / pdf.
     *
     * <p>{@code fileExtnNm}과 다르면 확장자가 어긋난 파일이라는 뜻이다 — 예를 들어 HWPX를
     * {@code .hwp}로 저장한 파일은 {@code FILE_EXTN_NM=hwp}, {@code ACTL_FILE_EXTN_NM=hwpx}가 된다.
     * 라우팅은 이 값으로 하고, 기존 적재 데이터·집계와의 호환을 위해 {@code FILE_EXTN_NM}은
     * 확장자 그대로 둔다.
     */
    private String actlFileExtnNm;
    /** 스캔본 여부 — OCR 경로로 처리됐으면 true. */
    private boolean scanYn;
    /** 문단·표가 문서 등장 순서로 섞여 담기는 본문 항목 목록. */
    private List<RawContent> content = new ArrayList<>();
    /** 추출된 이미지 메타 목록(저장 경로·크기). */
    private List<RawImage> images = new ArrayList<>();

    /** Jackson 역직렬화용 기본 생성자. */
    public RawDocument() {
    }

    /** 메타 3필드를 지정해 생성한다(content·images는 이후 채운다). */
    public RawDocument(String atchFileNm, String fileExtnNm, boolean scanYn) {
        this.atchFileNm = atchFileNm;
        this.fileExtnNm = fileExtnNm;
        this.scanYn = scanYn;
    }

    /** 원본 파일명을 반환한다. */
    @JsonProperty("atch_file_nm")
    public String getAtchFileNm() {
        return atchFileNm;
    }

    /** 원본 파일명을 설정한다. */
    public void setAtchFileNm(String atchFileNm) {
        this.atchFileNm = atchFileNm;
    }

    /** 확장자 기준 형식 식별자(hwp/hwpx/hml/pdf)를 반환한다. */
    @JsonProperty("file_extn_nm")
    public String getFileExtnNm() {
        return fileExtnNm;
    }

    /** 형식 식별자를 설정한다. */
    public void setFileExtnNm(String fileExtnNm) {
        this.fileExtnNm = fileExtnNm;
    }

    /** 내용으로 판정한 실제 형식(hwp/hwp3/hwpx/hml/pdf)을 반환한다. 판정 전이면 null. */
    @JsonProperty("actl_file_extn_nm")
    public String getActlFileExtnNm() {
        return actlFileExtnNm;
    }

    /** 내용으로 판정한 실제 형식을 설정한다(라우팅 계층이 채운다). */
    public void setActlFileExtnNm(String actlFileExtnNm) {
        this.actlFileExtnNm = actlFileExtnNm;
    }

    /** 스캔본 여부를 반환한다. */
    @JsonProperty("scan_yn")
    public boolean isScanYn() {
        return scanYn;
    }

    /** 스캔본 여부를 설정한다(OCR 경로가 true로 강제). */
    public void setScanYn(boolean scanYn) {
        this.scanYn = scanYn;
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
