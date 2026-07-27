package com.onnara.extract.common.table;

import com.onnara.extract.common.Labels;
import com.onnara.extract.common.Synonyms;
import com.onnara.extract.common.model.RawContent;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 표 해석 — raw 표 격자를 "서식 유형 + 라벨:값 목록"으로 바꾸는 중간 단계.
 *
 * <p>표를 읽는 규칙은 세 가지다.
 *
 * <ol>
 *   <li><b>목록표(header_list)</b> — 상단 1~{@value #MAX_HEADER_ROWS}행이 헤더면
 *       (셀의 {@value #HEADER_MAPPED_RATIO} 이상이 표준 필드로 매핑) 이후 각 행이
 *       독립 레코드가 된다. "피승인자" 아래 "주소/성명"이 오는 2단 병합 헤더는
 *       열마다 아래쪽 헤더를 우선해 읽는다.</li>
 *   <li><b>서식표 라벨/값 칸 쌍(label_pair)</b> — 라벨 칸 바로 다음 칸만 값으로 본다.
 *       빈 칸을 건너뛰며 값을 찾으면 병합 셀 때문에 값 칸이 빈 서식에서 옆 필드의
 *       라벨을 값으로 삼킨다.</li>
 *   <li><b>셀 안 라벨:값(in_cell)</b> — 문서 전체가 1열 표 안에 들어간 서식용.</li>
 * </ol>
 *
 * <p>매핑되지 않은 라벨도 값과 함께 남긴다 — 동의어 사전 보강의 근거 데이터다(§9).
 */
public final class TableInterpreter {

    /** 헤더형 목록표 판정: 헤더 행에서 표준 필드로 매핑돼야 하는 최소 비율. */
    private static final double HEADER_MAPPED_RATIO = 0.6;

    /** 다단(병합) 헤더로 인정할 최대 헤더 행 수. */
    private static final int MAX_HEADER_ROWS = 2;

    /** 인스턴스화 방지 — 정적 해석 함수만 제공하는 유틸리티 클래스. */
    private TableInterpreter() {
    }

    /** raw 문서의 표들을 해석해 중간 산출물 문서를 만든다. */
    public static TableDoc interpret(RawDocument raw, String engine) {
        return TableDoc.of(raw.getSourceFile(), raw.getFileType(), raw.isScanned(), engine,
                interpret(tablesOf(raw)));
    }

    /** raw 문서 content에서 표 항목만 순서대로 뽑는다. */
    public static List<RawTable> tablesOf(RawDocument raw) {
        List<RawTable> tables = new ArrayList<>();
        for (RawContent item : raw.getContent()) {
            if (item instanceof RawTable table) {
                tables.add(table);
            }
        }
        return tables;
    }

    /** 표 목록을 순서대로 해석한다. */
    public static List<InterpretedTable> interpret(List<RawTable> tables) {
        List<InterpretedTable> out = new ArrayList<>(tables.size());
        for (int i = 0; i < tables.size(); i++) {
            out.add(interpretOne(tables.get(i), i));
        }
        return out;
    }

    /**
     * 표 1개를 해석한다. 목록표로 읽히면 행별 레코드를, 아니면 문서 범위 레코드
     * 하나에 라벨/값 칸 쌍과 셀 안 라벨:값을 순서대로 담는다.
     */
    private static InterpretedTable interpretOne(RawTable table, int index) {
        TableGrid grid = TableGrid.of(table);
        int nRows = grid.rowCount();
        int nCols = 0;
        for (List<String> row : grid.grid()) {
            nCols = Math.max(nCols, row.size());
        }

        TableGrid.Cleaned cleaned = grid.cleaned();
        if (cleaned.grid().isEmpty()) {
            return new InterpretedTable(index, TableKind.UNKNOWN, nRows, nCols, 0,
                    grid.spanAware(), List.of(), List.of());
        }

        InterpretedTable headerList = tryHeaderList(index, grid, cleaned, nRows, nCols);
        if (headerList != null) {
            return headerList;
        }

        List<TableFact> facts = new ArrayList<>();
        facts.addAll(scanLabelPairs(grid));
        facts.addAll(scanInCellLabels(grid));
        if (facts.isEmpty()) {
            return new InterpretedTable(index, TableKind.UNKNOWN, nRows, nCols, 0,
                    grid.spanAware(), List.of(), List.of());
        }
        return new InterpretedTable(index, TableKind.LABEL_VALUE, nRows, nCols, 0,
                grid.spanAware(), List.of(),
                List.of(new TableRecord(TableRecord.DOCUMENT_SCOPE, List.copyOf(facts))));
    }

    // ── 목록표 ───────────────────────────────────────────────────

    /**
     * 목록표로 읽어본다. 헤더가 없거나 데이터 행에서 아무 값도 못 얻으면 null을
     * 반환해 서식표 경로로 넘긴다(헤더처럼 보였을 뿐인 표를 되살리기 위함).
     */
    private static InterpretedTable tryHeaderList(int index, TableGrid source,
                                                  TableGrid.Cleaned cleaned,
                                                  int nRows, int nCols) {
        List<List<String>> grid = cleaned.grid();
        if (grid.size() < 2 || grid.get(0).size() < 2) {
            return null;
        }
        int headerRows = 0;
        while (headerRows < Math.min(MAX_HEADER_ROWS, grid.size() - 1)
                && looksLikeHeaderRow(grid.get(headerRows))) {
            headerRows++;
        }
        if (headerRows == 0) {
            return null;
        }

        List<TableColumn> columns = readHeaderColumns(grid, cleaned, headerRows);

        List<TableRecord> records = new ArrayList<>();
        for (int r = headerRows; r < grid.size(); r++) {
            List<String> row = grid.get(r);
            List<TableFact> facts = new ArrayList<>();
            for (int c = 0; c < row.size() && c < columns.size(); c++) {
                String value = flatten(row.get(c));
                if (value.isEmpty()) {
                    continue;
                }
                TableColumn column = columns.get(c);
                int originRow = cleaned.rowIndex()[r];
                int originCol = cleaned.colIndex()[c];
                if (column.canonical() != null) {
                    facts.add(new TableFact(TableFact.ORIGIN_HEADER_COLUMN, originRow, originCol,
                            column.rawLabel(), column.label(), column.canonical(), value));
                } else if (Labels.acceptableExtra(column.label(), value)) {
                    facts.add(new TableFact(TableFact.ORIGIN_HEADER_COLUMN, originRow, originCol,
                            column.rawLabel(), column.label(), null, value));
                }
            }
            if (!facts.isEmpty()) {
                records.add(new TableRecord(cleaned.rowIndex()[r], List.copyOf(facts)));
            }
        }
        if (records.isEmpty()) {
            return null;
        }
        return new InterpretedTable(index, TableKind.HEADER_LIST, nRows, nCols, headerRows,
                source.spanAware(), List.copyOf(columns), List.copyOf(records));
    }

    /**
     * 열별 헤더 라벨을 읽는다. 병합 헤더에서는 아래쪽 행(더 구체적인 하위 라벨)부터
     * 표준 필드를 찾고, 안 되면 상·하위를 합친 형태("피승인자 성명")로도 사전을
     * 조회하며, 그래도 못 찾으면 하위 라벨을 extras 키 후보로 남긴다.
     */
    private static List<TableColumn> readHeaderColumns(List<List<String>> grid,
                                                       TableGrid.Cleaned cleaned,
                                                       int headerRows) {
        int nCols = grid.get(0).size();
        List<TableColumn> columns = new ArrayList<>(nCols);
        for (int c = 0; c < nCols; c++) {
            // 아래 행(하위 라벨)부터 위 행(상위 라벨) 순으로, 세로 병합 반복은 접는다
            List<String> levels = new ArrayList<>();
            for (int r = headerRows - 1; r >= 0; r--) {
                List<String> row = grid.get(r);
                String cell = c < row.size() ? row.get(c) : "";
                if (!cell.isEmpty()
                        && (levels.isEmpty() || !levels.get(levels.size() - 1).equals(cell))) {
                    levels.add(cell);
                }
            }
            String rawLabel = levels.isEmpty() ? "" : levels.get(0);
            Optional<String> canonical = Optional.empty();
            for (String level : levels) {
                canonical = Synonyms.canonicalFor(level);
                if (canonical.isPresent()) {
                    rawLabel = level;
                    break;
                }
            }
            // 하위 라벨 단독으로 못 찾으면 "상위 하위" 합성 형태로 조회한다
            // (사전에 "피승인자 성명"처럼 등재된 항목 대응)
            for (int i = 1; i < levels.size() && canonical.isEmpty(); i++) {
                String composite = levels.get(i) + " " + levels.get(0);
                canonical = Synonyms.canonicalFor(composite);
                if (canonical.isPresent()) {
                    rawLabel = composite;
                }
            }
            columns.add(new TableColumn(cleaned.colIndex()[c], rawLabel,
                    Synonyms.normalizeLabel(rawLabel), canonical.orElse(null)));
        }
        return columns;
    }

    /** 값 안의 줄바꿈·연속 공백을 한 칸으로 접는다 — OCR 셀의 시각적 줄바꿈이 값에 남지 않게. */
    private static String flatten(String value) {
        return value.replaceAll("[\\s\\u00A0\\u3000]+", " ").trim();
    }

    /** 행이 헤더로 보이는지: 병합 반복 제거 후 남은 라벨 셀 2개 이상 + 매핑 비율 충족. */
    private static boolean looksLikeHeaderRow(List<String> row) {
        List<String> cells = new ArrayList<>();
        for (String cell : row) {
            if (cells.isEmpty() || !cells.get(cells.size() - 1).equals(cell)) {
                cells.add(cell);
            }
        }
        cells.removeIf(String::isEmpty);
        if (cells.size() < 2) {
            return false;
        }
        int mapped = 0;
        for (String cell : cells) {
            if (Synonyms.canonicalFor(cell).isPresent()) {
                mapped++;
            }
        }
        return mapped >= Math.ceil(cells.size() * HEADER_MAPPED_RATIO);
    }

    // ── 서식표 ───────────────────────────────────────────────────

    /**
     * 라벨/값 칸 쌍을 읽는다(2열·4열 서식).
     *
     * <p>값은 라벨 바로 다음 칸만 본다 — 여러 칸을 건너뛰며 "다음 비어있지 않은 칸"을
     * 찾으면, 병합 셀로 값 칸이 빈 서식에서 옆 필드의 라벨을 값으로 잘못 채간다
     * (예: "관리청"의 값 칸이 빈 채로 "관리번호" 라벨을 삼킴).
     */
    private static List<TableFact> scanLabelPairs(TableGrid grid) {
        List<TableFact> facts = new ArrayList<>();
        for (int r = 0; r < grid.rowCount(); r++) {
            List<TableGrid.Cell> row = grid.logicalRow(r);
            boolean[] usedAsValue = new boolean[row.size()];
            for (int j = 0; j + 1 < row.size(); j++) {
                // 직전 쌍의 값으로 쓰인 칸은 라벨이 될 수 없다
                // (예: "선명 | 불명 | 선박번호 | 불명"에서 첫 '불명'이 라벨로 오탐되는 것 방지)
                if (usedAsValue[j]) {
                    continue;
                }
                String labelCell = row.get(j).text();
                if (labelCell.isEmpty() || labelCell.contains("\n")) {
                    continue;
                }
                TableGrid.Cell valueCell = row.get(j + 1);
                String value = flatten(valueCell.text());
                if (value.isEmpty() || Synonyms.canonicalFor(value).isPresent()) {
                    continue;
                }
                String normalized = Synonyms.normalizeLabel(labelCell);
                Optional<String> canonical = Synonyms.canonicalFor(labelCell);
                if (canonical.isPresent()) {
                    facts.add(new TableFact(TableFact.ORIGIN_LABEL_PAIR, r, valueCell.col(),
                            labelCell, normalized, canonical.get(), value));
                    usedAsValue[j + 1] = true;
                } else if (labelCell.indexOf(':') < 0 && labelCell.indexOf('：') < 0
                        && Labels.acceptableExtra(normalized, value)) {
                    facts.add(new TableFact(TableFact.ORIGIN_LABEL_PAIR, r, valueCell.col(),
                            labelCell, normalized, null, value));
                    usedAsValue[j + 1] = true;
                }
            }
        }
        return facts;
    }

    /** 셀 안의 "라벨 : 값" 줄을 읽는다(1열 고시문 서식 등). */
    private static List<TableFact> scanInCellLabels(TableGrid grid) {
        List<TableFact> facts = new ArrayList<>();
        for (int r = 0; r < grid.rowCount(); r++) {
            for (TableGrid.Cell cell : grid.logicalRow(r)) {
                String text = cell.text();
                if (text.isEmpty() || text.indexOf(':') < 0 && text.indexOf('：') < 0) {
                    continue;
                }
                for (Labels.Pair pair : Labels.scan(List.of(text.split("\n")))) {
                    String normalized = Synonyms.normalizeLabel(pair.label());
                    Optional<String> canonical = Synonyms.canonicalFor(pair.label());
                    if (canonical.isPresent()) {
                        facts.add(new TableFact(TableFact.ORIGIN_IN_CELL, r, cell.col(),
                                pair.label(), normalized, canonical.get(), pair.value()));
                    } else if (Labels.acceptableExtra(normalized, pair.value())) {
                        facts.add(new TableFact(TableFact.ORIGIN_IN_CELL, r, cell.col(),
                                pair.label(), normalized, null, pair.value()));
                    }
                }
            }
        }
        return facts;
    }
}
