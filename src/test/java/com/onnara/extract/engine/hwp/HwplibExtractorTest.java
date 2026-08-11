package com.onnara.extract.engine.hwp;

import com.onnara.extract.TestFixtures;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HwplibExtractor} HWP 추출의 samples/ 기반 회귀 테스트. */
class HwplibExtractorTest {

    private final HwplibExtractor extractor = new HwplibExtractor();

    /**
     * 배포용 문서는 라이브러리에 넘기기 전에 걸러 낸다.
     *
     * <p>그냥 넘기면 hwplib이 암호문을 구조로 읽으려다 {@code "This is not paragraph."}로
     * 죽어, 원인 체인 어디에도 암호 얘기가 남지 않는다. 그러면 실패 목록에서 진짜 손상과
     * 구분되지 않아 재저장하면 살아날 문서가 그대로 버려진다.
     */
    @Test
    void refusesDistributionDocumentWithAnActionableMessage() {
        Path file = TestFixtures.sample("배포용 공유수면 점용사용허가 고시(광양시).hwp");

        IOException e = assertThrows(IOException.class, () -> extractor.extractRaw(file));

        assertTrue(e.getMessage().contains("배포용 문서"), e.getMessage());
        assertTrue(e.getMessage().contains("일반 문서로 저장"), e.getMessage());
    }

    /** 문단·표가 등장 순서로 추출되고 표 span이 유효한지 검증한다. */
    @Test
    void extractsParagraphsAndTablesInOrder() throws Exception {
        Path file = TestFixtures.sample("공유수면 점용사용 허가 고시문.hwp");
        RawDocument raw = extractor.extractRaw(file);

        assertEquals("hwp", raw.getFileType());
        assertFalse(raw.isScanned());
        assertFalse(raw.getContent().isEmpty(), "content가 비어있음");
        assertTrue(raw.getContent().stream().anyMatch(c -> c instanceof RawParagraph),
                "문단이 하나도 없음");

        raw.getContent().stream()
                .filter(c -> c instanceof RawTable)
                .map(c -> (RawTable) c)
                .forEach(t -> {
                    assertTrue(t.getNRows() > 0);
                    assertEquals(t.getNRows(), t.getGrid().size());
                    t.getCells().forEach(cell -> {
                        assertTrue(cell.getRowSpan() >= 1);
                        assertTrue(cell.getColSpan() >= 1);
                    });
                });
    }

    /** saveImages 결과가 extractRaw의 이미지 순서·이름·크기와 일치하고 매직바이트 확장자를 쓰는지 검증한다. */
    @Test
    void saveImagesMatchesExtractRawOrderAndNames(@TempDir Path tempDir) throws Exception {
        Path file = TestFixtures.sample("공유수면 점용사용 승인사항 고시.hwp");
        RawDocument raw = extractor.extractRaw(file);
        List<Path> saved = extractor.saveImages(file, tempDir);

        assertEquals(raw.getImages().size(), saved.size(), "이미지 개수 불일치");
        for (int i = 0; i < saved.size(); i++) {
            assertEquals(raw.getImages().get(i).getName(), saved.get(i).getFileName().toString());
            assertTrue(Files.exists(saved.get(i)));
            assertEquals(raw.getImages().get(i).getSize(), Files.size(saved.get(i)));
            String name = saved.get(i).getFileName().toString();
            assertTrue(name.matches(".+\\.(jpg|png|bmp|gif|bin)$"), "매직바이트 확장자 아님: " + name);
        }
    }
}
