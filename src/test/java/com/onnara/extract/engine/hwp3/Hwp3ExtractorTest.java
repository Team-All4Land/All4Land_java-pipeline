package com.onnara.extract.engine.hwp3;

import com.onnara.extract.TestFixtures;
import com.onnara.extract.common.Mapper;
import com.onnara.extract.common.model.NoticeRecord;
import com.onnara.extract.common.model.RawCell;
import com.onnara.extract.common.model.RawContent;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawTable;
import com.onnara.extract.common.model.SchemaResult;
import com.onnara.extract.engine.Extractor;
import com.onnara.extract.engine.ExtractorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 한글 3.0 추출 엔진({@link Hwp3Extractor})과 파이프라인 연결의 테스트. */
class Hwp3ExtractorTest {

    /** 동해시 공유수면 고시(한글 3.0) 픽스처. */
    private static Path sample() {
        return TestFixtures.sample("한글3.0 공유수면 고시(동해시).hwp");
    }

    /** 확장자가 아니라 내용으로 판정된 형식 키로 엔진이 선택돼야 한다. */
    @Test
    void isSelectedByContentEvenThoughExtensionIsHwp() {
        Extractor extractor = ExtractorRegistry.forFile(sample());

        assertInstanceOf(Hwp3Extractor.class, extractor);
        assertEquals("hwp3", extractor.engineName());
        // 확장자 키(hwp)로는 여전히 hwplib이 선택된다 — 라우팅만 내용 기반이다
        assertSame(ExtractorRegistry.forExtension("hwp3"), extractor);
    }

    /** 표가 raw 계약(행·열·병합 span·grid)으로 옮겨진다. */
    @Test
    void mapsTableIntoRawContract() throws IOException {
        RawDocument raw = new Hwp3Extractor().extractRaw(sample());

        RawTable table = raw.getContent().stream()
                .filter(RawTable.class::isInstance)
                .map(RawTable.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(8, table.getNRows());
        assertEquals(5, table.getNCols());
        assertEquals(8, table.getGrid().size());
        assertEquals(5, table.getGrid().get(0).size());
        assertEquals("게재(요청)일자", table.getGrid().get(0).get(0));

        // 병합 셀 텍스트는 덮인 칸마다 복제된다(HwplibExtractor와 같은 규칙)
        assertEquals(table.getGrid().get(1).get(3), table.getGrid().get(1).get(4));

        RawCell notiSn = table.getCells().stream()
                .filter(c -> c.getRow() == 1 && c.getCol() == 3)
                .findFirst()
                .orElseThrow();
        assertEquals(2, notiSn.getColSpan());
    }

    /** file_type은 엔진이 아니라 라우팅 계층이 확정하므로, 엔진 자체는 확장자를 그대로 둔다. */
    @Test
    void reportsHwpAsFileType() throws IOException {
        assertEquals("hwp", new Hwp3Extractor().extractRaw(sample()).getFileExtn());
    }

    /** 이미지가 없는 문서는 빈 목록을 낸다 — saveImages도 같은 순서 계약을 지킨다. */
    @Test
    void keepsImageOrderContract(@TempDir Path outDir) throws IOException {
        Hwp3Extractor extractor = new Hwp3Extractor();

        RawDocument raw = extractor.extractRaw(sample());
        List<Path> saved = extractor.saveImages(sample(), outDir);

        assertEquals(raw.getImages().size(), saved.size());
        for (int i = 0; i < saved.size(); i++) {
            assertEquals(raw.getImages().get(i).getName(), saved.get(i).getFileName().toString());
        }
    }

    /**
     * 최종 확인 — 한글 3.0 문서가 표준 필드까지 채워져야 "파이프라인을 통과했다"고 할 수 있다.
     * 추출만 되고 매핑이 비면 DB에 빈 행이 쌓일 뿐이다.
     */
    @Test
    void fillsStandardFieldsEndToEnd() throws IOException {
        RawDocument raw = new Hwp3Extractor().extractRaw(sample());

        SchemaResult schema = Mapper.mapToSchema(raw, "hwp3");
        NoticeRecord record = schema.getRecords().get(0);

        assertEquals("동해시", record.bodyAgncyNm());
        assertEquals("고시 제2023-74호", record.notiNo());
        assertTrue(record.notiTtl().startsWith("공유수면 점용사용 허가"), record.notiTtl());
        assertTrue(record.extras().get("내용").contains("「공유수면 관리 및 매립에 관한 법률」"),
                record.extras().get("내용"));
    }

    /** 본문 항목이 문서 등장 순서를 지키는지 — 표 앞뒤 문단이 뒤섞이면 매핑이 어긋난다. */
    @Test
    void keepsContentInDocumentOrder() throws IOException {
        List<RawContent> content = new Hwp3Extractor().extractRaw(sample()).getContent();

        assertTrue(content.stream().anyMatch(RawTable.class::isInstance), "표가 없습니다");
    }
}
