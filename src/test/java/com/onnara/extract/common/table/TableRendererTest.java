package com.onnara.extract.common.table;

import com.onnara.extract.common.model.RawCell;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** raw JSON 표의 HTML 복원({@link TableRenderer}) 단위 테스트. */
class TableRendererTest {

    /**
     * 가로 병합이 있는 표(hml/hwpx 엔진 산출물 모양) — grid는 병합 텍스트를 세 칸에 반복해
     * 담지만, HTML은 colspan 하나로 접혀야 원본과 같아 보인다.
     */
    @Test
    void restoresHorizontalMergeAsColspan() {
        RawTable table = new RawTable(1, 4,
                List.of(new RawCell(0, 0, 1, 3, "관 리 청"),
                        new RawCell(0, 3, 1, 1, "군산지방해양수산청")),
                List.of(List.of("관 리 청", "관 리 청", "관 리 청", "군산지방해양수산청")));

        String html = TableRenderer.toHtml(table);

        assertTrue(html.contains("<td colspan=\"3\">관 리 청</td>"), html);
        assertTrue(html.contains("<td>군산지방해양수산청</td>"), html);
        // 반복된 칸이 그대로 나오면 원본에 없던 칸이 생긴다
        assertEquals(2, countCells(html), html);
    }

    /** 세로 병합은 아래 행에서 칸 자체가 사라져야 한다(rowspan이 그 자리를 채운다). */
    @Test
    void restoresVerticalMergeAsRowspan() {
        RawTable table = new RawTable(2, 2,
                List.of(new RawCell(0, 0, 2, 1, "구분"),
                        new RawCell(0, 1, 1, 1, "값1"),
                        new RawCell(1, 1, 1, 1, "값2")),
                List.of(List.of("구분", "값1"), List.of("구분", "값2")));

        String html = TableRenderer.toHtml(table);

        assertTrue(html.contains("<td rowspan=\"2\">구분</td>"), html);
        assertEquals(3, countCells(html), html);
    }

    /**
     * span 정보가 없으면(PDF·OCR 경로) 병합을 추측하지 않는다 — 우연히 값이 같은 이웃 칸까지
     * 접히면 검수용 산출물이 원본에 없던 구조를 보여 주게 된다.
     */
    @Test
    void doesNotGuessMergesWhenSpansAreAbsent() {
        RawTable table = new RawTable(1, 3, null,
                List.of(List.of("같음", "같음", "다름")));

        String html = TableRenderer.toHtml(table);

        assertEquals(3, countCells(html), html);
        assertFalse(html.contains("colspan"), html);
    }

    /** 셀 목록이 있어도 병합이 하나도 없으면 격자 그대로다(span=1 목록에 의미가 없다). */
    @Test
    void treatsAllOnesSpanListAsPlainGrid() {
        RawTable table = new RawTable(1, 2,
                List.of(new RawCell(0, 0, 1, 1, "가"), new RawCell(0, 1, 1, 1, "나")),
                List.of(List.of("가", "나")));

        String html = TableRenderer.toHtml(table);

        assertEquals(2, countCells(html), html);
        assertFalse(html.contains("span"), html);
    }

    /** 셀 텍스트의 HTML 특수문자는 이스케이프하고, 줄바꿈은 살려야 한다. */
    @Test
    void escapesMarkupAndKeepsLineBreaks() {
        RawTable table = new RawTable(1, 1, null,
                List.of(List.of("<b>면적</b> & \"단위\"\n㎡")));

        String html = TableRenderer.toHtml(table);

        assertTrue(html.contains("&lt;b&gt;면적&lt;/b&gt; &amp; &quot;단위&quot;<br>㎡"), html);
    }

    /** 행마다 열 수가 다른 격자에서도 표가 어긋나지 않아야 한다. */
    @Test
    void padsRaggedRows() {
        RawTable table = new RawTable(2, 3, null,
                List.of(List.of("가", "나", "다"), List.of("라")));

        String html = TableRenderer.toHtml(table);

        assertEquals(6, countCells(html), html);
    }

    /** 문서 렌더링은 표를 등장 순서대로 담고, 병합 정보 유무를 밝혀야 한다. */
    @Test
    void rendersWholeDocumentWithTableOrderAndSpanNotice() {
        RawDocument raw = new RawDocument("고시문.hml", "hml", false);
        raw.getContent().add(new RawParagraph("본문"));
        raw.getContent().add(new RawTable(1, 2,
                List.of(new RawCell(0, 0, 1, 2, "병합")),
                List.of(List.of("병합", "병합"))));
        raw.getContent().add(new RawTable(1, 1, null, List.of(List.of("단순"))));

        String html = TableRenderer.toHtmlDocument(raw);

        assertTrue(html.startsWith("<!DOCTYPE html>"), html.substring(0, 40));
        assertTrue(html.contains("<title>고시문.hml — 표</title>"), html);
        assertTrue(html.contains("표 2개"), html);
        assertTrue(html.contains("병합 정보 있음(원본 병합 복원)"), html);
        assertTrue(html.contains("병합 정보 없음(격자 그대로)"), html);
        assertTrue(html.indexOf("<h2>표 1</h2>") < html.indexOf("<h2>표 2</h2>"),
                "등장 순서를 지켜야 함");
    }

    /** 표가 없는 문서도 빈 HTML이 아니라 "없음"을 말해야 한다(검수자가 판단할 수 있게). */
    @Test
    void saysSoWhenDocumentHasNoTables() {
        RawDocument raw = new RawDocument("문단만.pdf", "pdf", false);
        raw.getContent().add(new RawParagraph("표 없는 문서"));

        String html = TableRenderer.toHtmlDocument(raw);

        assertTrue(html.contains("추출된 표가 없습니다"), html);
        assertTrue(html.contains("표 0개"), html);
    }

    /** 격자가 없는 표도 예외 없이 빈 표로 그려야 한다. */
    @Test
    void handlesTableWithoutGrid() {
        assertEquals("<table class=\"raw\"></table>",
                TableRenderer.toHtml(new RawTable(0, 0, null, null)));
    }

    /** {@code <td} 등장 횟수 = 실제로 그려진 칸 수. */
    private static int countCells(String html) {
        return html.split("<td", -1).length - 1;
    }
}
