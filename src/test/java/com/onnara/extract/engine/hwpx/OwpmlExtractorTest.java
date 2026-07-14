package com.onnara.extract.engine.hwpx;

import com.onnara.extract.TestFixtures;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwpmlExtractorTest {

    private final OwpmlExtractor extractor = new OwpmlExtractor();

    /**
     * 인라인 태그(&lt;hp:fwSpace/&gt; 등) 잘림 회귀 테스트.
     * NormalText만 읽으면 "방 치 선 박  제 거 공 고"처럼 전각 공백이 섞인 텍스트가
     * 첫 태그 지점에서 끊긴다 — 전체 토큰이 이어져 있어야 한다.
     */
    @Test
    void inlineTagsDoNotTruncateText() throws Exception {
        Path file = TestFixtures.sample("방치선박 제거공고(군산-2).hwpx");
        RawDocument raw = extractor.extractRaw(file);

        String allText = fullText(raw);
        // 원문(section0.xml)에 fwSpace가 존재하는 문서 — 핵심 토큰이 온전해야 함
        assertTrue(allText.contains("방 치 선 박  제 거 공 고"),
                "fwSpace 지점에서 텍스트가 잘림: " + snippet(allText));
        assertTrue(allText.contains("공유수면 관리 및 매립에 관한 법률"));
        assertTrue(allText.contains("전북특별자치도 군산시 오식도동"));
    }

    @Test
    void restoresMergedCellSpans() throws Exception {
        Path file = TestFixtures.sample("방치선박 제거공고(군산-2).hwpx");
        RawDocument raw = extractor.extractRaw(file);

        List<RawTable> tables = raw.getContent().stream()
                .filter(c -> c instanceof RawTable)
                .map(c -> (RawTable) c)
                .collect(Collectors.toList());
        assertFalse(tables.isEmpty(), "표가 없음");

        boolean hasMergedCell = tables.stream()
                .flatMap(t -> t.getCells().stream())
                .anyMatch(cell -> cell.getRowSpan() > 1 || cell.getColSpan() > 1);
        assertTrue(hasMergedCell, "병합 셀 span이 복원되지 않음");

        for (RawTable t : tables) {
            assertEquals(t.getNRows(), t.getGrid().size(), "grid 행 수 불일치");
        }
    }

    @Test
    void saveImagesMatchesExtractRawOrderAndNames(@TempDir Path tempDir) throws Exception {
        Path file = TestFixtures.sample("방치선박 제거공고(부안-11).hwpx");
        RawDocument raw = extractor.extractRaw(file);
        List<Path> saved = extractor.saveImages(file, tempDir);

        assertEquals(raw.getImages().size(), saved.size());
        for (int i = 0; i < saved.size(); i++) {
            assertEquals(raw.getImages().get(i).getName(), saved.get(i).getFileName().toString());
        }
    }

    private static String fullText(RawDocument raw) {
        StringBuilder sb = new StringBuilder();
        raw.getContent().forEach(c -> {
            if (c instanceof RawParagraph p) {
                sb.append(p.getText()).append('\n');
            } else if (c instanceof RawTable t && t.getGrid() != null) {
                t.getGrid().forEach(row -> row.forEach(cell -> sb.append(cell).append('\n')));
            }
        });
        return sb.toString();
    }

    private static String snippet(String text) {
        return text.substring(0, Math.min(200, text.length()));
    }
}
