package com.onnara.extract.common.table;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 표가 기여하는 레코드 1건 — 이 표에서 읽어낸 라벨:값 묶음.
 *
 * @param row    목록표면 원본 데이터 행 인덱스. 서식표는 문서 전체에 기여하므로 -1
 * @param fields 이 레코드에 담길 라벨:값 목록(읽어낸 순서 유지)
 */
@JsonPropertyOrder({"row", "fields"})
public record TableRecord(
        @JsonProperty("row") int row,
        @JsonProperty("fields") List<TableFact> fields) {

    /** 문서 전체에 기여하는 레코드(서식표)의 행 인덱스 표기. */
    public static final int DOCUMENT_SCOPE = -1;

    /** 이 레코드가 문서 전체 범위인지(목록표의 개별 행이 아닌지). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean documentScope() {
        return row == DOCUMENT_SCOPE;
    }
}
