package com.onnara.extract.engine.hml;

import com.onnara.extract.common.model.RawContent;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * HML 표 캡션 추출 검증.
 *
 * <p>HML 캡션은 지금까지 <b>어느 경로에도 걸리지 않고 통째로 버려졌다</b> —
 * {@code getText}는 TABLE 하위를 건너뛰고 {@code parseTable}은 ROW/CELL만 훑기 때문이다.
 * {@code samples/}의 실물 HML에도 {@code <CAPTION>}이 없어 회귀로 잡히지 않았다.
 */
class HmlCaptionTest {

    @TempDir
    Path tempDir;

    private final HmlExtractor extractor = new HmlExtractor();

    /** 표 캡션이 표의 전용 필드로 들어와야 한다. */
    @Test
    void readsTableCaption() throws Exception {
        Path file = write("캡션.hml", hml("""
                <SHAPEOBJECT InstId="1"><CAPTION Side="Top"><PARALIST>
                <P><TEXT><CHAR>&lt;표 1&gt; 공유수면 점용·사용허가 내역</CHAR></TEXT></P>
                </PARALIST></CAPTION></SHAPEOBJECT>"""));

        RawDocument raw = extractor.extractRaw(file);

        assertEquals("<표 1> 공유수면 점용·사용허가 내역", firstTable(raw).getCaption());
    }

    /** 캡션이 없으면 null이다. */
    @Test
    void leavesCaptionNullWhenThereIsNone() throws Exception {
        Path file = write("캡션없음.hml", hml("<SHAPEOBJECT InstId=\"1\"/>"));

        assertNull(firstTable(raw(file)).getCaption());
    }

    /**
     * 캡션 텍스트가 셀 텍스트로 새면 안 된다 — 표 내용이 오염되면 매핑이 그대로 틀어진다.
     */
    @Test
    void doesNotLeakCaptionIntoCellText() throws Exception {
        Path file = write("분리.hml", hml("""
                <SHAPEOBJECT InstId="1"><CAPTION Side="Top"><PARALIST>
                <P><TEXT><CHAR>표 제목</CHAR></TEXT></P>
                </PARALIST></CAPTION></SHAPEOBJECT>"""));

        RawTable table = firstTable(raw(file));

        assertEquals("허가번호", table.getGrid().get(0).get(0));
        assertEquals("표 제목", table.getCaption());
    }

    private RawDocument raw(Path file) throws Exception {
        return extractor.extractRaw(file);
    }

    /** 1행 1열 표 하나를 담은 최소 HML 문서 — shapeObject 자리에 캡션을 끼워 넣는다. */
    private static String hml(String shapeObject) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <HWPML Version="2.8"><BODY><SECTION Id="0">
                <P><TEXT><CHAR>본문 문단</CHAR></TEXT>
                <TABLE RowCount="1" ColCount="1">%s
                <ROW><CELL ColAddr="0" RowAddr="0" ColSpan="1" RowSpan="1">
                <PARALIST><P><TEXT><CHAR>허가번호</CHAR></TEXT></P></PARALIST>
                </CELL></ROW></TABLE></P>
                </SECTION></BODY></HWPML>
                """.formatted(shapeObject);
    }

    private Path write(String name, String content) throws Exception {
        Path path = tempDir.resolve(name);
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    /** raw 문서에서 첫 번째 표를 꺼낸다. */
    private static RawTable firstTable(RawDocument raw) {
        for (RawContent content : raw.getContent()) {
            if (content instanceof RawTable table) {
                return table;
            }
        }
        throw new AssertionError("추출 결과에 표가 없습니다");
    }
}
