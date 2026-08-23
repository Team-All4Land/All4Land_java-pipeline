package com.onnara.extract.common.table;

import com.onnara.extract.common.model.RawCell;
import com.onnara.extract.common.model.RawContent;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawTable;

import java.util.ArrayList;
import java.util.List;

/**
 * raw JSON의 표를 사람이 볼 수 있는 HTML로 되돌린다 — 추출 결과를 눈으로 검수하기 위한 계층.
 *
 * <p>{@code raw.json}의 {@code grid}는 병합 셀의 텍스트를 덮인 칸마다 반복해 담는다. 그대로 표로
 * 그리면 원본에서 한 칸이던 것이 세 칸으로 보여, 표를 잘못 읽은 것인지 원래 그런 서식인지 구분되지
 * 않는다. 그래서 {@code cells}의 span을 살려 <b>원본 병합 구조 그대로</b> 그린다.
 *
 * <p><b>병합 정보가 없으면 추측하지 않는다.</b> PDF·OCR 경로는 span이 전부 1이라
 * "옆 칸과 내용이 같으면 병합"이라는 근사밖에 쓸 수 없는데, 그러면 우연히 값이 같은 이웃 칸까지
 * 접혀 원본에 없던 병합이 생긴다. 검수용 산출물에서 없던 구조를 만들어 보여 주는 쪽이,
 * 있는 구조를 못 보여 주는 쪽보다 나쁘다. 그런 표는 격자 그대로 1×1로 낸다
 * ({@link TableGrid#spanAware()}가 같은 기준을 쓴다).
 */
public final class TableRenderer {

    /** 인스턴스화 방지 — 정적 렌더링 함수만 제공하는 유틸리티 클래스. */
    private TableRenderer() {
    }

    /**
     * raw 문서의 표 전체를 등장 순서대로 담은 완결된 HTML 문서를 만든다.
     *
     * <p>CSS를 인라인으로 넣어 파일 하나만 열면 되게 한다(외부 의존 없음).
     */
    public static String toHtmlDocument(RawDocument raw) {
        List<RawTable> tables = tablesOf(raw);
        String title = raw.getAtchFileNm() == null ? "표" : raw.getAtchFileNm();

        StringBuilder out = new StringBuilder();
        out.append("<!DOCTYPE html>\n<html lang=\"ko\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<title>").append(escape(title)).append(" — 표</title>\n")
                .append("<style>\n").append(css()).append("</style>\n")
                .append("</head>\n<body>\n");

        out.append("<h1>").append(escape(title)).append("</h1>\n")
                .append("<p class=\"meta\">형식 ").append(escape(nullToDash(raw.getFileExtnNm())))
                .append(" · 스캔본 ").append(raw.isScanYn() ? "예" : "아니오")
                .append(" · 표 ").append(tables.size()).append("개</p>\n");

        if (tables.isEmpty()) {
            out.append("<p class=\"empty\">이 문서에서 추출된 표가 없습니다.</p>\n");
        }
        for (int i = 0; i < tables.size(); i++) {
            RawTable table = tables.get(i);
            boolean spanAware = TableGrid.of(table).spanAware();
            out.append("<h2>표 ").append(i + 1).append("</h2>\n")
                    .append("<p class=\"meta\">").append(table.getNRows()).append("행 × ")
                    .append(table.getNCols()).append("열 · 병합 정보 ")
                    .append(spanAware ? "있음(원본 병합 복원)" : "없음(격자 그대로)")
                    .append("</p>\n")
                    .append(toHtml(table)).append('\n');
        }

        out.append("</body>\n</html>\n");
        return out.toString();
    }

    /** 표 하나를 {@code <table>} 조각으로 만든다. */
    public static String toHtml(RawTable table) {
        List<List<String>> grid = table.getGrid();
        if (grid == null || grid.isEmpty()) {
            return "<table class=\"raw\"></table>";
        }
        int width = 0;
        for (List<String> row : grid) {
            width = Math.max(width, row == null ? 0 : row.size());
        }
        // 어느 칸을 어느 앵커 셀이 덮는지 — span이 없으면 null이고, 그때는 격자를 그대로 그린다
        RawCell[][] anchors = anchorMap(table, grid.size(), width);

        StringBuilder out = new StringBuilder("<table class=\"raw\">\n");
        // 캡션은 표의 첫 자식이어야 한다(HTML 규격) — 검수 화면에서 "이 표가 무엇인지"가
        // 격자 바로 위에 붙어야 표를 제대로 읽었는지 눈으로 가릴 수 있다
        if (table.getCaption() != null) {
            out.append("<caption>").append(escape(table.getCaption())).append("</caption>\n");
        }
        for (int r = 0; r < grid.size(); r++) {
            out.append("<tr>");
            List<String> row = grid.get(r);
            for (int c = 0; c < width; c++) {
                if (anchors == null) {
                    out.append(cellHtml(valueAt(row, c), 1, 1));
                    continue;
                }
                RawCell owner = anchors[r][c];
                if (owner == null) {
                    // span 정보가 닿지 않는 칸(셀 목록이 격자를 다 덮지 못한 경우)은 그대로 그린다
                    out.append(cellHtml(valueAt(row, c), 1, 1));
                } else if (owner.getRow() == r && owner.getCol() == c) {
                    out.append(cellHtml(owner.getText(),
                            Math.max(1, owner.getRowSpan()), Math.max(1, owner.getColSpan())));
                }
                // 앵커가 아닌 칸은 덮인 칸이므로 아무것도 내지 않는다
            }
            out.append("</tr>\n");
        }
        return out.append("</table>").toString();
    }

    /**
     * "칸 → 그 칸을 소유한 앵커 셀" 표를 만든다. 실제 병합이 하나도 없거나 셀 정보가 없으면 null
     * (= 격자를 그대로 그린다). 판정 기준은 {@link TableGrid#of}와 같다.
     */
    private static RawCell[][] anchorMap(RawTable table, int rows, int width) {
        List<RawCell> cells = table.getCells();
        if (cells == null || cells.isEmpty() || rows == 0 || width == 0) {
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
        RawCell[][] anchors = new RawCell[rows][width];
        for (RawCell cell : cells) {
            for (int dr = 0; dr < Math.max(1, cell.getRowSpan()); dr++) {
                for (int dc = 0; dc < Math.max(1, cell.getColSpan()); dc++) {
                    int r = cell.getRow() + dr;
                    int c = cell.getCol() + dc;
                    if (r >= 0 && r < rows && c >= 0 && c < width) {
                        anchors[r][c] = cell;
                    }
                }
            }
        }
        return anchors;
    }

    /** 셀 하나 — span이 1이면 속성을 붙이지 않아 HTML이 읽기 쉽게 남는다. */
    private static String cellHtml(String text, int rowSpan, int colSpan) {
        StringBuilder out = new StringBuilder("<td");
        if (rowSpan > 1) {
            out.append(" rowspan=\"").append(rowSpan).append('"');
        }
        if (colSpan > 1) {
            out.append(" colspan=\"").append(colSpan).append('"');
        }
        return out.append('>').append(escape(text)).append("</td>").toString();
    }

    /** 문서 content에서 표만 등장 순서대로 뽑는다. */
    private static List<RawTable> tablesOf(RawDocument raw) {
        List<RawTable> tables = new ArrayList<>();
        if (raw == null || raw.getContent() == null) {
            return tables;
        }
        for (RawContent item : raw.getContent()) {
            if (item instanceof RawTable table) {
                tables.add(table);
            }
        }
        return tables;
    }

    /** 행에서 c번째 값(행이 짧으면 빈 문자열). */
    private static String valueAt(List<String> row, int c) {
        return row == null || c >= row.size() ? "" : row.get(c);
    }

    /** HTML 이스케이프 + 줄바꿈을 {@code <br>}로 — 셀 안의 여러 줄이 한 줄로 뭉치지 않게 한다. */
    private static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\r' -> { }
                case '\n' -> out.append("<br>");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }

    /** null이면 대시. */
    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** 인라인 스타일 — 병합 구조가 눈에 들어오도록 테두리를 접어 그린다. */
    private static String css() {
        return """
                body { font-family: "Malgun Gothic", "Noto Sans KR", sans-serif;
                       margin: 2rem; color: #1a1a1a; }
                h1 { font-size: 1.25rem; }
                h2 { font-size: 1rem; margin-top: 2rem; }
                .meta { color: #666; font-size: 0.85rem; margin: 0.25rem 0 0.75rem; }
                .empty { color: #666; }
                table.raw { border-collapse: collapse; margin-bottom: 1rem; max-width: 100%; }
                table.raw td { border: 1px solid #999; padding: 4px 8px;
                               vertical-align: top; font-size: 0.9rem; }
                table.raw caption { caption-side: top; text-align: left; font-weight: 600;
                                    font-size: 0.9rem; padding-bottom: 0.35rem; }
                """;
    }
}
