package com.onnara.extract.model;

import java.util.List;

/**
 * 추출된 표 하나를 나타내는 모델.
 * grid는 병합 셀을 실제 크기만큼 복제해서 채운 2차원 배열(CSV/DataFrame 변환에 용이),
 * cells는 원본 병합 정보(rowSpan/colSpan)를 보존한 셀 단위 리스트(정밀 복원용).
 */
public class ExtractedTable {
    private String tableId;
    private int rowCount;
    private int colCount;
    private List<List<String>> grid;
    private List<ExtractedCell> cells;
    private String caption;

    public ExtractedTable(String tableId, int rowCount, int colCount,
                           List<List<String>> grid, List<ExtractedCell> cells) {
        this.tableId = tableId;
        this.rowCount = rowCount;
        this.colCount = colCount;
        this.grid = grid;
        this.cells = cells;
    }

    public String getTableId() { return tableId; }
    public int getRowCount() { return rowCount; }
    public int getColCount() { return colCount; }
    public List<List<String>> getGrid() { return grid; }
    public List<ExtractedCell> getCells() { return cells; }
    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }
}
