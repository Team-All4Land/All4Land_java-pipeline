package com.onnara.extract.engine.hwp;

import com.onnara.extract.TestFixtures;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;
import com.onnara.extract.detect.FailureClassifier;
import com.onnara.extract.detect.FailureKind;
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
     * <b>배포용을 진입점에서 통째로 막지는 않는다</b> — 막으면 읽히던 배포용 문서까지 실패로
     * 돌아선다(영광군 2건은 실제로 읽힌다 → {@code ProtectedDocumentTest}).
     *
     * <p>이 광양시 건은 hwplib이 복호화 마지막 블록을 잃어 본문 끝이 잘리고
     * {@code "This is not paragraph."}로 죽는다. 그때 남길 답은 "암호 해제본을 받아 오라"가 아니라
     * <b>"다시 저장하면 읽힌다"</b>여야 한다 — 암호 문제가 아니라 파일도 우리 파서도 멀쩡하다.
     */
    @Test
    void failingDistributionDocumentIsBlamedOnUsNotOnAPassword() {
        Path file = TestFixtures.sample("배포용 공유수면 점용사용허가 고시(광양시).hwp");

        IOException e = assertThrows(IOException.class, () -> extractor.extractRaw(file));
        FailureClassifier.Result result = FailureClassifier.classify(file, e);

        assertEquals(FailureKind.DISTRIBUTION_TRUNCATED, result.kind());
        assertTrue(result.detail().contains("배포용"), result.detail());
    }

    /** 문단·표가 등장 순서로 추출되고 표 span이 유효한지 검증한다. */
    @Test
    void extractsParagraphsAndTablesInOrder() throws Exception {
        Path file = TestFixtures.sample("공유수면 점용사용 허가 고시문.hwp");
        RawDocument raw = extractor.extractRaw(file);

        assertEquals("hwp", raw.getFileExtnNm());
        assertFalse(raw.isScanYn());
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
