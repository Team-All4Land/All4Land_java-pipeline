package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * raw JSON 계약의 표 항목:
 * {"type": "table", "n_rows", "n_cols", "cells", "grid"}.
 *
 * <p>매퍼는 grid(행×열 2차원 배열)만 사용한다. cells는 병합 셀 span 보존용 선택 필드.
 */
@JsonPropertyOrder({"type", "n_rows", "n_cols", "cells", "grid", "source_image"})
public class RawTable extends RawContent {

    /** 행 수. */
    private int nRows;
    /** 열 수. */
    private int nCols;
    /** 병합 span을 보존한 셀 목록(선택) — hml/hwpx 엔진이 채운다. */
    private List<RawCell> cells;
    /** 행×열 텍스트 2차원 배열 — 매퍼가 사용하는 주 표현. */
    private List<List<String>> grid;

    /** Jackson 역직렬화용 기본 생성자. */
    public RawTable() {
    }

    /** 행·열 수, 셀 목록, 격자를 지정해 표를 생성한다. */
    public RawTable(int nRows, int nCols, List<RawCell> cells, List<List<String>> grid) {
        this.nRows = nRows;
        this.nCols = nCols;
        this.cells = cells;
        this.grid = grid;
    }

    /** content 항목 구분자 — 항상 "table". */
    @Override
    public String getType() {
        return "table";
    }

    /** 행 수를 반환한다. */
    @JsonProperty("n_rows")
    public int getNRows() {
        return nRows;
    }

    /** 행 수를 설정한다. */
    public void setNRows(int nRows) {
        this.nRows = nRows;
    }

    /** 열 수를 반환한다. */
    @JsonProperty("n_cols")
    public int getNCols() {
        return nCols;
    }

    /** 열 수를 설정한다. */
    public void setNCols(int nCols) {
        this.nCols = nCols;
    }

    /** 병합 span 셀 목록을 반환한다(없을 수 있음). */
    @JsonProperty("cells")
    public List<RawCell> getCells() {
        return cells;
    }

    /** 병합 span 셀 목록을 설정한다. */
    public void setCells(List<RawCell> cells) {
        this.cells = cells;
    }

    /** 행×열 텍스트 격자를 반환한다. */
    @JsonProperty("grid")
    public List<List<String>> getGrid() {
        return grid;
    }

    /** 행×열 텍스트 격자를 설정한다. */
    public void setGrid(List<List<String>> grid) {
        this.grid = grid;
    }
}
