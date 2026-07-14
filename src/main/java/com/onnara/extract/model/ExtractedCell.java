package com.onnara.extract.model;

/** 표 셀 하나의 원본 정보 (rowAddr/colAddr/rowSpan/colSpan 포함, 정밀 복원용) */
public class ExtractedCell {
    private int row;
    private int col;
    private int rowSpan;
    private int colSpan;
    private String text;

    public ExtractedCell(int row, int col, int rowSpan, int colSpan, String text) {
        this.row = row;
        this.col = col;
        this.rowSpan = rowSpan;
        this.colSpan = colSpan;
        this.text = text;
    }

    public int getRow() { return row; }
    public int getCol() { return col; }
    public int getRowSpan() { return rowSpan; }
    public int getColSpan() { return colSpan; }
    public String getText() { return text; }
}
