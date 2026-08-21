package com.onnara.extract.common.table;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 목록표(header_list)의 열 1개에 대한 헤더 해석 결과.
 *
 * @param index     원본 격자의 열 인덱스
 * @param rawLabel  헤더 셀 원문
 * @param label     정규화된 헤더 라벨
 * @param itemCd 매핑된 표준 필드명. null이면 이 열의 값들은 extras로 간다
 */
@JsonPropertyOrder({"index", "raw_label", "label", "canonical"})
public record TableColumn(
        @JsonProperty("index") int index,
        @JsonProperty("raw_label") String rawLabel,
        @JsonProperty("label") String label,
        @JsonProperty("canonical") String itemCd) {
}
