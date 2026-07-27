package com.onnara.extract.common.table;

import com.onnara.extract.common.model.RawCell;
import com.onnara.extract.common.model.RawTable;

import java.util.ArrayList;
import java.util.List;

/**
 * 표 격자의 논리 구조 — 병합(span) 정보를 반영해 "실제 칸"을 복원한다.
 *
 * <p>raw 격자는 병합 셀의 텍스트를 덮인 칸마다 반복해 담는다. 라벨/값 쌍을 읽으려면
 * 이 반복을 하나로 접어야 하는데, 접는 방법이 두 가지다.
 *
 * <ul>
 *   <li><b>span 기반(정확)</b> — {@link RawTable#getCells()}에 병합 정보가 있으면
 *       어느 칸이 같은 셀인지 정확히 안다. hml·hwpx 엔진이 이 정보를 채운다.</li>
 *   <li><b>연속 중복 기반(근사)</b> — span 정보가 없으면(PDF·OCR 경로) 옆 칸과
 *       내용이 같은 것을 병합으로 간주한다. 우연히 같은 값이 이웃한 칸도 접히는
 *       한계가 있어, span 정보가 있을 때는 쓰지 않는다.</li>
 * </ul>
 */
public final class TableGrid {

    /** 앞뒤 공백을 정리한 원본 배치 격자(병합 반복 포함). */
    private final List<List<String>> grid;

    /** 각 칸을 소유한 셀의 앵커 좌표(row*width+col 인코딩). span 정보가 없으면 null. */
    private final int[][] owner;

    /** 논리 행에 담기는 칸 하나 — 원본 열 위치를 함께 보존해 추적 가능성을 남긴다. */
    public record Cell(int col, String text) {
    }

    /**
     * 빈 행/열을 제거한 격자와, 제거 후 인덱스를 원본 인덱스로 되돌리는 표.
     *
     * @param grid     정리된 격자
     * @param rowIndex 정리 격자의 행 인덱스 → 원본 행 인덱스
     * @param colIndex 정리 격자의 열 인덱스 → 원본 열 인덱스
     */
    public record Cleaned(List<List<String>> grid, int[] rowIndex, int[] colIndex) {
    }

    /** 격자와 소유 셀 표를 지정해 생성한다. */
    private TableGrid(List<List<String>> grid, int[][] owner) {
        this.grid = grid;
        this.owner = owner;
    }

    /**
     * raw 표에서 격자를 만든다. {@code cells}에 실제 병합(span &gt; 1)이 있으면
     * 소유 셀 표를 구성해 정확한 병합 판정을 준비한다.
     */
    public static TableGrid of(RawTable table) {
        List<List<String>> trimmed = trimGrid(table.getGrid());
        return new TableGrid(trimmed, buildOwner(table, trimmed));
    }

    /**
     * 셀 목록의 span을 펼쳐 "칸 → 소유 셀" 표를 만든다. 병합이 하나도 없거나
     * 셀 정보가 없으면 null을 반환해 연속 중복 근사로 넘긴다.
     */
    private static int[][] buildOwner(RawTable table, List<List<String>> trimmed) {
        List<RawCell> cells = table.getCells();
        if (cells == null || cells.isEmpty() || trimmed.isEmpty()) {
            return null;
        }
        boolean merged = false;
        for (RawCell cell : cells) {
            if (cell.getRowSpan() > 1 || cell.getColSpan() > 1) {
                merged = true;
                break;
            }
        }
        if (!merged) {
            return null;
        }

        int width = 0;
        for (List<String> row : trimmed) {
            width = Math.max(width, row.size());
        }
        int[][] owner = new int[trimmed.size()][width];
        for (int[] row : owner) {
            java.util.Arrays.fill(row, -1);
        }
        for (RawCell cell : cells) {
            int anchor = cell.getRow() * width + cell.getCol();
            for (int dr = 0; dr < Math.max(1, cell.getRowSpan()); dr++) {
                for (int dc = 0; dc < Math.max(1, cell.getColSpan()); dc++) {
                    int r = cell.getRow() + dr;
                    int c = cell.getCol() + dc;
                    if (r >= 0 && r < owner.length && c >= 0 && c < width) {
                        owner[r][c] = anchor;
                    }
                }
            }
        }
        return owner;
    }

    /** 앞뒤 공백을 정리한 원본 배치 격자를 반환한다. */
    public List<List<String>> grid() {
        return grid;
    }

    /** 행 수. */
    public int rowCount() {
        return grid.size();
    }

    /** 병합 정보를 실제로 활용할 수 있는지 — 검토 문서에 표기해 근사 여부를 드러낸다. */
    public boolean spanAware() {
        return owner != null;
    }

    /**
     * 한 행을 논리 칸 목록으로 편다 — 가로 병합으로 반복된 칸을 하나로 접는다.
     *
     * <p>세로 병합으로 위 행에서 이어진 칸은 접지 않는다. 그 칸은 이 행에서도
     * 라벨 역할을 하므로 값 칸을 찾으려면 남아 있어야 한다.
     */
    public List<Cell> logicalRow(int r) {
        List<String> row = grid.get(r);
        List<Cell> out = new ArrayList<>(row.size());
        for (int c = 0; c < row.size(); c++) {
            if (c > 0 && isHorizontalContinuation(r, c)) {
                continue;
            }
            out.add(new Cell(c, row.get(c)));
        }
        return out;
    }

    /** (r, c)가 왼쪽 칸과 같은 셀인지 — span을 알면 정확히, 모르면 내용 비교로 근사한다. */
    private boolean isHorizontalContinuation(int r, int c) {
        if (owner != null) {
            return owner[r][c] != -1 && owner[r][c] == owner[r][c - 1];
        }
        // span 정보가 없을 때만: 바로 왼쪽 칸과 내용이 같으면 병합 반복으로 본다.
        // (연속 중복을 접는 것과 같은 결과 — 접힌 칸은 늘 직전 칸과 값이 같다)
        List<String> row = grid.get(r);
        return row.get(c).equals(row.get(c - 1));
    }

    /**
     * 완전히 빈 행/열을 제거한 격자를 원본 인덱스 표와 함께 반환한다
     * ({@link com.onnara.extract.common.Tables#cleanGrid}와 동일 규칙).
     */
    public Cleaned cleaned() {
        List<Integer> keepRows = new ArrayList<>();
        for (int r = 0; r < grid.size(); r++) {
            for (String cell : grid.get(r)) {
                if (!cell.isEmpty()) {
                    keepRows.add(r);
                    break;
                }
            }
        }
        if (keepRows.isEmpty()) {
            return new Cleaned(List.of(), new int[0], new int[0]);
        }

        int nCols = grid.get(keepRows.get(0)).size();
        List<Integer> keepCols = new ArrayList<>();
        for (int c = 0; c < nCols; c++) {
            for (int r : keepRows) {
                List<String> row = grid.get(r);
                if (c < row.size() && !row.get(c).isEmpty()) {
                    keepCols.add(c);
                    break;
                }
            }
        }

        List<List<String>> out = new ArrayList<>(keepRows.size());
        for (int r : keepRows) {
            List<String> row = grid.get(r);
            List<String> newRow = new ArrayList<>(keepCols.size());
            for (int c : keepCols) {
                newRow.add(c < row.size() ? row.get(c) : "");
            }
            out.add(newRow);
        }
        return new Cleaned(out, toArray(keepRows), toArray(keepCols));
    }

    /** Integer 목록을 int 배열로 옮긴다. */
    private static int[] toArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /** 격자의 각 셀에서 null→"" 치환과 앞뒤 공백 제거만 수행한다(칸 배치는 보존). */
    private static List<List<String>> trimGrid(List<List<String>> rows) {
        List<List<String>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (List<String> row : rows) {
            List<String> trimmed = new ArrayList<>(row.size());
            for (String cell : row) {
                trimmed.add(cell == null ? "" : cell.trim());
            }
            out.add(trimmed);
        }
        return out;
    }
}
