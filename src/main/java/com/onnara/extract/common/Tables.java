package com.onnara.extract.common;

import com.onnara.extract.common.model.RawCell;
import com.onnara.extract.common.model.RawTable;

import java.util.ArrayList;
import java.util.List;

/**
 * 형식 공통 표 헬퍼 — Python common/tables.py 포팅.
 *
 * <p>여러 추출 엔진이 셀 격자를 동일한 표 형식(n_rows/n_cols/cells/grid)으로
 * 변환할 때 공유한다.
 */
public final class Tables {

    private Tables() {
    }

    /** null 정리 후 완전히 빈 행/열을 제거한다 (clean_grid 포팅). */
    public static List<List<String>> cleanGrid(List<List<String>> rows) {
        List<List<String>> grid = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> cleaned = new ArrayList<>(row.size());
            boolean any = false;
            for (String c : row) {
                String v = c == null ? "" : c.trim();
                cleaned.add(v);
                if (!v.isEmpty()) {
                    any = true;
                }
            }
            if (any) {
                grid.add(cleaned);
            }
        }
        if (grid.isEmpty()) {
            return grid;
        }
        int nCols = grid.get(0).size();
        List<Integer> keepCols = new ArrayList<>();
        for (int j = 0; j < nCols; j++) {
            for (List<String> row : grid) {
                if (j < row.size() && !row.get(j).isEmpty()) {
                    keepCols.add(j);
                    break;
                }
            }
        }
        List<List<String>> out = new ArrayList<>(grid.size());
        for (List<String> row : grid) {
            List<String> newRow = new ArrayList<>(keepCols.size());
            for (int j : keepCols) {
                newRow.add(j < row.size() ? row.get(j) : "");
            }
            out.add(newRow);
        }
        return out;
    }

    /** 격자를 표준 표(n_rows/n_cols/cells/grid)로 변환한다 (grid_to_table 포팅). */
    public static RawTable gridToTable(List<List<String>> grid) {
        List<RawCell> cells = new ArrayList<>();
        for (int i = 0; i < grid.size(); i++) {
            List<String> row = grid.get(i);
            for (int j = 0; j < row.size(); j++) {
                cells.add(new RawCell(i, j, 1, 1, row.get(j)));
            }
        }
        int nCols = grid.isEmpty() ? 0 : grid.get(0).size();
        return new RawTable(grid.size(), nCols, cells, grid);
    }
}