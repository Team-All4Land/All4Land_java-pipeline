package com.onnara.extract.engine.hwp3;

import com.onnara.extract.TestFixtures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 한글 3.0 고시문으로 {@link Hwp3Reader}를 검증한다. */
class Hwp3ReaderTest {

    /** 동해시 공유수면 고시 — 8행×5열 표 하나로 이루어진 문서. */
    private static Hwp3Document read() throws IOException {
        return Hwp3Reader.read(TestFixtures.sample("한글3.0 공유수면 고시(동해시).hwp"));
    }

    /** 표에서 특정 칸의 셀을 찾는다. */
    private static Hwp3Document.Cell cellAt(Hwp3Document.Table table, int row, int col) {
        return table.cells().stream()
                .filter(c -> c.row() == row && c.col() == col)
                .findFirst()
                .orElseThrow(() -> new AssertionError("셀이 없습니다: r" + row + "c" + col));
    }

    /** 문서의 첫 표를 반환한다. */
    private static Hwp3Document.Table firstTable(Hwp3Document doc) {
        return doc.items().stream()
                .filter(Hwp3Document.Table.class::isInstance)
                .map(Hwp3Document.Table.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("표가 없습니다"));
    }

    /**
     * 표의 격자 크기 — 한글 3.0은 행·열 번호를 저장하지 않고 좌표만 남기므로,
     * 경계값 정렬로 8행×5열이 복원돼야 한다.
     */
    @Test
    void restoresTableGridFromCellCoordinates() throws IOException {
        Hwp3Document.Table table = firstTable(read());

        assertEquals(8, table.rows());
        assertEquals(5, table.cols());
        assertEquals(18, table.cells().size());
    }

    /** 셀 텍스트가 라벨:값 그대로 복원돼야 매퍼가 표준 필드를 채울 수 있다. */
    @Test
    void readsCellTextInGridOrder() throws IOException {
        Hwp3Document.Table table = firstTable(read());

        assertEquals("게재(요청)일자", cellAt(table, 0, 0).text());
        assertEquals("2023-07-25", cellAt(table, 0, 1).text());
        assertEquals("담당부서", cellAt(table, 0, 2).text());
        assertEquals("고시공고번호", cellAt(table, 1, 2).text());
        assertEquals("제목", cellAt(table, 3, 0).text());
    }

    /** 병합 span도 좌표에서 복원된다 — 값 셀이 여러 칸을 덮는 서식이 흔하다. */
    @Test
    void restoresMergedCellSpans() throws IOException {
        Hwp3Document.Table table = firstTable(read());

        Hwp3Document.Cell noticeNo = cellAt(table, 1, 3);
        assertEquals(1, noticeNo.rowSpan());
        assertEquals(2, noticeNo.colSpan());
        assertEquals("동해시 고시 제2023-74호", noticeNo.text());

        Hwp3Document.Cell body = cellAt(table, 4, 0);
        assertEquals(4, body.rowSpan());
        assertEquals("내용", body.text());
    }

    /**
     * 특수문자 구간이 살아 있어야 법령명이 원문 그대로 나온다.
     * 이 한 줄이 깨지면 제목·내용 필드가 통째로 훼손된다.
     */
    @Test
    void keepsStatuteNameBrackets() throws IOException {
        String body = cellAt(firstTable(read()), 4, 1).text();

        assertTrue(body.startsWith("「공유수면 관리 및 매립에 관한 법률」"), body);
        assertFalse(body.contains(String.valueOf(Hwp3Chars.UNMAPPED)), "변환 실패 문자가 남았습니다: " + body);
    }

    /**
     * 문단 리스트의 종료 표시(빈 문단)도 43바이트를 소비한다는 규칙이 지켜지는지를
     * 문서 끝까지 읽히는 것으로 확인한다 — 한 번이라도 어긋나면 뒤쪽 셀이 전부 깨진다.
     * 마지막 행의 값이 온전하다는 것은 중첩 문단 리스트를 18번 통과했다는 뜻이다.
     */
    @Test
    void keepsCursorAlignedAcrossNestedParagraphLists() throws IOException {
        Hwp3Document.Table table = firstTable(read());

        assertEquals("3. 게재기간 : 고시일로부터 14일간", cellAt(table, 7, 1).text());
    }

    /**
     * 본문 글자 수는 스캔 판별의 근거다 — 표 셀 텍스트가 모두 세어져야 한다.
     *
     * <p>병합 셀은 한 번만 센다(격자로 펼친 값이 아니라 셀 목록을 센다). 이 문서는
     * 라벨·값·본문을 합쳐 400자 남짓이라, 스캔 임계치(1자)와는 자릿수가 다르게 떨어져 있다.
     */
    @Test
    void countsTableCellTextAsBody() throws IOException {
        assertTrue(read().bodyChars() > 300, "본문 글자 수가 너무 적습니다");
    }

    /** 서명이 다른 파일은 읽지 않는다 — 라우팅이 어긋났을 때 조용히 쓰레기를 내면 안 된다. */
    @Test
    void rejectsFileWithoutHwp3Signature() {
        Path hwp5 = TestFixtures.sample("공유수면 점용사용 승인사항 고시.hwp");

        IOException error = assertThrows(IOException.class, () -> Hwp3Reader.read(hwp5));
        assertNotNull(error.getMessage());
        assertTrue(error.getMessage().contains("서명"), error.getMessage());
    }

    /** 임베디드 이미지 목록은 비어 있을 수 있다(이 문서는 그림이 본문 흐름에만 있다). */
    @Test
    void exposesEmbeddedImageList() throws IOException {
        List<Hwp3Document.Image> images = read().images();

        assertNotNull(images);
    }
}
