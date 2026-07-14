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

    private int row;
    private int col;
    private int rowSpan = 1;
    private int colSpan = 1;
    private String text;

    public RawCell() {
    }

    public RawCell(int row, int col, int rowSpan, int colSpan, String text) {
        this.row = row;
        this.col = col;
        this.rowSpan = rowSpan;
        this.colSpan = colSpan;
        this.text = text;
    }

    @JsonProperty("row")
    public int getRow() {
        return row;
    }

    public void setRow(int row) {
        this.row = row;
    }

    @JsonProperty("col")
    public int getCol() {
        return col;
    }

    public void setCol(int col) {
        this.col = col;
    }

    @JsonProperty("row_span")
    public int getRowSpan() {
        return rowSpan;
    }

    public void setRowSpan(int rowSpan) {
        this.rowSpan = rowSpan;
    }

    @JsonProperty("col_span")
    public int getColSpan() {
        return colSpan;
    }

    public void setColSpan(int colSpan) {
        this.colSpan = colSpan;
    }

    @JsonProperty("text")
    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}