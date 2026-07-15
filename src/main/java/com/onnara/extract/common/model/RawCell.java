package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * raw JSON 계약의 표 셀: {"row", "col", "row_span", "col_span", "text"}.
 *
 * <p>병합 셀 구조를 보존할 수 있는 엔진(hml, hwpx)이 span을 채운다.
 * PDF 엔진은 격자 기반이므로 span은 항상 1이다.
 */
@JsonPropertyOrder({"row", "col", "row_span", "col_span", "text"})
public class RawCell {

    /** 셀의 시작 행 인덱스(0부터). */
    private int row;
    /** 셀의 시작 열 인덱스(0부터). */
    private int col;
    /** 세로 병합 행 수(병합 없으면 1). */
    private int rowSpan = 1;
    /** 가로 병합 열 수(병합 없으면 1). */
    private int colSpan = 1;
    /** 셀 텍스트. */
    private String text;

    /** Jackson 역직렬화용 기본 생성자. */
    public RawCell() {
    }

    /** 위치·병합 span·텍스트를 지정해 셀을 생성한다. */
    public RawCell(int row, int col, int rowSpan, int colSpan, String text) {
        this.row = row;
        this.col = col;
        this.rowSpan = rowSpan;
        this.colSpan = colSpan;
        this.text = text;
    }

    /** 시작 행 인덱스를 반환한다. */
    @JsonProperty("row")
    public int getRow() {
        return row;
    }

    /** 시작 행 인덱스를 설정한다. */
    public void setRow(int row) {
        this.row = row;
    }

    /** 시작 열 인덱스를 반환한다. */
    @JsonProperty("col")
    public int getCol() {
        return col;
    }

    /** 시작 열 인덱스를 설정한다. */
    public void setCol(int col) {
        this.col = col;
    }

    /** 세로 병합 행 수를 반환한다. */
    @JsonProperty("row_span")
    public int getRowSpan() {
        return rowSpan;
    }

    /** 세로 병합 행 수를 설정한다. */
    public void setRowSpan(int rowSpan) {
        this.rowSpan = rowSpan;
    }

    /** 가로 병합 열 수를 반환한다. */
    @JsonProperty("col_span")
    public int getColSpan() {
        return colSpan;
    }

    /** 가로 병합 열 수를 설정한다. */
    public void setColSpan(int colSpan) {
        this.colSpan = colSpan;
    }

    /** 셀 텍스트를 반환한다. */
    @JsonProperty("text")
    public String getText() {
        return text;
    }

    /** 셀 텍스트를 설정한다. */
    public void setText(String text) {
        this.text = text;
    }
}
