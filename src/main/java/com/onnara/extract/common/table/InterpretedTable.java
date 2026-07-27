package com.onnara.extract.common.table;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 표 1개의 해석 결과 — 서식 유형 판정과 거기서 읽어낸 라벨:값들.
 *
 * @param index      문서 안에서 몇 번째 표인지(0부터)
 * @param kind       판정된 서식 유형
 * @param nRows      원본 행 수
 * @param nCols      원본 열 수
 * @param headerRows 목록표로 판정된 경우 헤더로 쓰인 행 수(아니면 0)
 * @param spanAware  병합 정보를 정확히 알고 읽었는지(false면 내용 비교 근사)
 * @param columns    목록표의 열별 헤더 해석(서식표는 빈 목록)
 * @param records    이 표가 기여하는 레코드들
 */
@JsonPropertyOrder({"index", "kind", "n_rows", "n_cols", "header_rows", "span_aware",
        "columns", "records"})
public record InterpretedTable(
        @JsonProperty("index") int index,
        @JsonProperty("kind") TableKind kind,
        @JsonProperty("n_rows") int nRows,
        @JsonProperty("n_cols") int nCols,
        @JsonProperty("header_rows") int headerRows,
        @JsonProperty("span_aware") boolean spanAware,
        @JsonProperty("columns") List<TableColumn> columns,
        @JsonProperty("records") List<TableRecord> records) {
}
