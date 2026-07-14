package com.onnara.extract.engine.hml;

import com.onnara.extract.TestFixtures;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HmlExtractorTest {

    private final HmlExtractor extractor = new HmlExtractor();

    @Test
    void parsesUtf8BomAndTableGrid() throws Exception {
        Path file = TestFixtures.sample(
                "1_공유수면 점용·사용 실시계획 준공검사확인증 발급 고시(인천시종합건설본부, 영종도 해안순환도로).hml");
        RawDocument raw = extractor.extractRaw(file);

        assertEquals("hml", raw.getFileType());
        assertFalse(raw.isScanned());
        assertFalse(raw.getContent().isEmpty());

        List<RawTable> tables = raw.getContent().stream()
                .filter(c -> c instanceof RawTable)
                .map(c -> (RawTable) c)
                .collect(Collectors.toList());
        assertFalse(tables.isEmpty(), "표가 없음 (RowCount/ColCount 속성 파싱 확인 대상)");
        for (RawTable t : tables) {
            assertEquals(t.getNRows(), t.getGrid().size(), "n_rows != grid.size()");
            if (t.getNRows() > 0) {
                assertEquals(t.getNCols(), t.getGrid().get(0).size(), "n_cols != grid row width");
            }
            t.getCells().forEach(cell -> {
                assertTrue(cell.getRowSpan() >= 1);
                assertTrue(cell.getColSpan() >= 1);
            });
        }
    }

    @Test
    void decodesEmbeddedBase64Image() throws Exception {
        Path file = TestFixtures.sample("8_공유수면 점용 사용 변경허가 고시(한국해양소년단).hml");
        RawDocument raw = extractor.extractRaw(file);

        assertFalse(raw.getImages().isEmpty(), "BINDATA 이미지가 디코딩되지 않음");
        assertTrue(raw.getImages().get(0).getSize() > 0);
    }

    @Test
    void saveImagesMatchesExtractRawOrderAndNames(@TempDir Path tempDir) throws Exception {
        Path file = TestFixtures.sample("8_공유수면 점용 사용 변경허가 고시(한국해양소년단).hml");
        RawDocument raw = extractor.extractRaw(file);
        List<Path> saved = extractor.saveImages(file, tempDir);

        assertEquals(raw.getImages().size(), saved.size());
        for (int i = 0; i < saved.size(); i++) {
            assertEquals(raw.getImages().get(i).getName(), saved.get(i).getFileName().toString());
            assertTrue(Files.exists(saved.get(i)));
        }
    }
}
