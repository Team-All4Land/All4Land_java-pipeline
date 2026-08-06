package com.onnara.extract.detect;

import com.onnara.extract.TestFixtures;
import com.onnara.extract.common.Mapper;
import com.onnara.extract.common.model.NoticeRecord;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.SchemaResult;
import com.onnara.extract.engine.Extractor;
import com.onnara.extract.engine.ExtractorRegistry;
import com.onnara.extract.engine.hwp3.Hwp3Extractor;
import com.onnara.extract.engine.hwpx.OwpmlExtractor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 확장자와 내용이 어긋난 실제 파일들이 파이프라인을 통과하는지 확인하는 회귀 테스트.
 *
 * <p>세 픽스처 모두 확장자가 {@code .hwp}지만 내용은 각각 HWPX·HWPX·한글 3.0이다.
 * 확장자만 보고 hwplib으로 보내던 시절에는 셋 다 판별 단계에서 실패했다.
 */
class ContentRoutingTest {

    /** {@code .hwp}로 저장된 HWPX 픽스처 둘. */
    private static List<Path> renamedHwpx() {
        return List.of(
                TestFixtures.sample("개명 hwpx - 공유수면 점사용 변경허가(SNNC).hwp"),
                TestFixtures.sample("개명 hwpx - 공유수면 점사용 허가.hwp"));
    }

    /** .hwp로 저장된 HWPX는 hwpxlib 엔진으로 가야 한다. */
    @Test
    void routesRenamedHwpxToOwpml() {
        for (Path file : renamedHwpx()) {
            Extractor extractor = ExtractorRegistry.forFile(file);

            assertInstanceOf(OwpmlExtractor.class, extractor, file.toString());
            assertEquals("owpml", extractor.engineName());
        }
    }

    /** 한글 3.0은 전용 엔진으로 가야 한다 — 확장자는 HWP 5.0과 구별되지 않는다. */
    @Test
    void routesLegacyHwp3ToItsOwnEngine() {
        Extractor extractor =
                ExtractorRegistry.forFile(TestFixtures.sample("한글3.0 공유수면 고시(동해시).hwp"));

        assertInstanceOf(Hwp3Extractor.class, extractor);
    }

    /** 스캔 판별도 내용 기반이라 개명 파일에서 예외를 던지지 않는다. */
    @Test
    void detectsScanStatusForRenamedFiles() {
        for (Path file : renamedHwpx()) {
            assertFalse(DetectorRegistry.isScanned(file), file.toString());
        }
        assertFalse(DetectorRegistry.isScanned(
                TestFixtures.sample("한글3.0 공유수면 고시(동해시).hwp")));
    }

    /** 판별 집계는 확장자가 아니라 실제 형식으로 갈려야 소계가 오염되지 않는다. */
    @Test
    void surveyCountsByRealFormat() {
        List<Path> files = List.of(
                TestFixtures.sample("한글3.0 공유수면 고시(동해시).hwp"),
                TestFixtures.sample("개명 hwpx - 공유수면 점사용 변경허가(SNNC).hwp"));

        ScanSurvey survey = ScanSurvey.of(files);

        assertEquals(0, survey.failed());
        assertEquals(2, survey.nativeCount());
        assertEquals("hwp3", survey.entries().get(0).ext());
        assertEquals("hwpx", survey.entries().get(1).ext());
    }

    /**
     * 통과의 기준은 "예외가 안 난다"가 아니라 표준 필드가 채워지는 것이다 —
     * 개명 HWPX 두 건이 기관·고시번호·신청인까지 복원돼야 한다.
     */
    @Test
    void fillsStandardFieldsForRenamedHwpx() throws IOException {
        for (Path file : renamedHwpx()) {
            Extractor extractor = ExtractorRegistry.forFile(file);
            RawDocument raw = extractor.extractRaw(file);

            SchemaResult schema = Mapper.mapToSchema(raw, extractor.engineName());
            NoticeRecord record = schema.getRecords().get(0);

            assertEquals("여수지방해양수산청", record.agency(), file.toString());
            assertNotNull(record.noticeNo(), file.toString());
            assertNotNull(record.applicantName(), file.toString());
        }
    }
}
