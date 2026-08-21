package com.onnara.extract.common.table;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 표에서 읽어낸 라벨:값 1건과 그 출처.
 *
 * <p>매핑 성공 여부({@code itemCd})와 원본 좌표를 함께 남겨, 스키마 결과만 보고는
 * 알 수 없는 "이 값이 표 어디에서 왜 그 필드로 갔는가"를 추적할 수 있게 한다.
 *
 * @param origin    읽어낸 규칙: label_pair(라벨/값 칸 쌍) / in_cell(셀 안 "라벨: 값" 줄)
 *                  / header_column(헤더 열 라벨)
 * @param row       원본 격자의 행 인덱스
 * @param col       값이 있던 원본 격자의 열 인덱스
 * @param rawLabel  표에 적힌 라벨 원문
 * @param label     정규화된 라벨 — 미매핑이면 이 값이 extras 키가 된다
 * @param itemCd 매핑된 표준 필드명. null이면 미매핑(extras 행)
 * @param value     값
 */
@JsonPropertyOrder({"origin", "row", "col", "raw_label", "label", "canonical", "mapped", "value"})
public record TableFact(
        @JsonProperty("origin") String origin,
        @JsonProperty("row") int row,
        @JsonProperty("col") int col,
        @JsonProperty("raw_label") String rawLabel,
        @JsonProperty("label") String label,
        @JsonProperty("canonical") String itemCd,
        @JsonProperty("value") String value) {

    /** 라벨/값 칸이 짝을 이룬 서식표에서 읽음. */
    public static final String ORIGIN_LABEL_PAIR = "label_pair";

    /** 셀 안의 "라벨 : 값" 줄에서 읽음. */
    public static final String ORIGIN_IN_CELL = "in_cell";

    /** 목록표의 헤더 열 라벨에서 읽음. */
    public static final String ORIGIN_HEADER_COLUMN = "header_column";

    /** 표준 필드로 매핑됐는지 — 검토 시 미매핑 행만 골라보기 위해 직렬화한다. */
    @JsonProperty("mapped")
    public boolean mapped() {
        return itemCd != null;
    }
}
