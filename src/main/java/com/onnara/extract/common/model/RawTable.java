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
@JsonPropertyOrder({"type", "n_rows", "n_cols", "cells", "grid"})
public class RawTable extends RawContent {

    private int nRows;
    private int nCols;
    private List<RawCell> cells;
    private List<List<String>> grid;

    public RawTable() {
    }

    public RawTable(int nRows, int nCols, List<RawCell> cells, List<List<String>> grid) {
        this.nRows = nRows;
        this.nCols = nCols;
        this.cells = cells;
        this.grid = grid;
    }

    @Override
    public String getType() {
        return "table";
    }

    @JsonProperty("n_rows")
    public int getNRows() {
        return nRows;
    }

    public void setNRows(int nRows) {
        this.nRows = nRows;
    }

    @JsonProperty("n_cols")
    public int getNCols() {
        return nCols;
    }

    public void setNCols(int nCols) {
        this.nCols = nCols;
    }

    @JsonProperty("cells")
    public List<RawCell> getCells() {
        return cells;
    }

    public void setCells(List<RawCell> cells) {
        this.cells = cells;
    }

    @JsonProperty("grid")
    public List<List<String>> getGrid() {
        return grid;
    }

    public void setGrid(List<List<String>> grid) {
        this.grid = grid;
    }
}