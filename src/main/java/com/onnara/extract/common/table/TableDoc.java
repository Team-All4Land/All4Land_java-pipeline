package com.onnara.extract.common.table;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 표 해석 중간 산출물({@code *.tables.json})의 최상위 문서.
 *
 * <p>raw JSON과 표준 스키마 JSON 사이에 놓이는 단계다. 표준 스키마는 "어느 필드가
 * 무슨 값이 됐는가"만 남기고 표 구조를 버리기 때문에, 매핑이 틀렸을 때 원인이
 * 표 읽기인지 사전 누락인지 구분할 수 없다. 이 단계는 그 사이를 드러낸다 —
 * 표를 어떤 서식으로 판정했고, 어느 칸에서 무슨 라벨을 읽었고, 그 라벨이 사전에
 * 있었는지까지 남긴다.
 *
 * @param atchFileNm 원본 파일명
 * @param fileExtnNm   형식 식별자(hwp / hwpx / hml / pdf)
 * @param scanYn    스캔본 여부
 * @param extcEngnNm     추출 엔진 식별자
 * @param summary    집계 — 사전 보강 대상을 빠르게 보기 위한 요약
 * @param tables     표별 해석 결과
 */
@JsonPropertyOrder({"atch_file_nm", "file_extn_nm", "scan_yn", "extc_engn_nm", "summary", "tables"})
public record TableDoc(
        @JsonProperty("atch_file_nm") String atchFileNm,
        @JsonProperty("file_extn_nm") String fileExtnNm,
        @JsonProperty("scan_yn") boolean scanYn,
        @JsonProperty("extc_engn_nm") String extcEngnNm,
        @JsonProperty("summary") Summary summary,
        @JsonProperty("tables") List<InterpretedTable> tables) {

    /**
     * 문서 단위 집계.
     *
     * @param tableCount     표 개수
     * @param factCount      읽어낸 라벨:값 총 건수
     * @param mappedCount    표준 필드로 매핑된 건수
     * @param unmappedCount  미매핑(extras행) 건수
     * @param unmappedLabels 미매핑 라벨 목록(중복 제거, 등장 순) — 사전 보강 후보
     */
    @JsonPropertyOrder({"table_count", "fact_count", "mapped_count", "unmapped_count",
            "unmapped_labels"})
    public record Summary(
            @JsonProperty("table_count") int tableCount,
            @JsonProperty("fact_count") int factCount,
            @JsonProperty("mapped_count") int mappedCount,
            @JsonProperty("unmapped_count") int unmappedCount,
            @JsonProperty("unmapped_labels") List<String> unmappedLabels) {
    }

    /** 표 해석 결과들을 집계해 문서를 만든다. */
    public static TableDoc of(String atchFileNm, String fileExtnNm, boolean scanYn, String extcEngnNm,
                              List<InterpretedTable> tables) {
        int facts = 0;
        int mapped = 0;
        Map<String, Boolean> unmapped = new LinkedHashMap<>();
        for (InterpretedTable table : tables) {
            for (TableRecord record : table.records()) {
                for (TableFact fact : record.fields()) {
                    facts++;
                    if (fact.mapped()) {
                        mapped++;
                    } else {
                        unmapped.put(fact.label(), Boolean.TRUE);
                    }
                }
            }
        }
        Summary summary = new Summary(tables.size(), facts, mapped, facts - mapped,
                new ArrayList<>(unmapped.keySet()));
        return new TableDoc(atchFileNm, fileExtnNm, scanYn, extcEngnNm, summary, tables);
    }
}
