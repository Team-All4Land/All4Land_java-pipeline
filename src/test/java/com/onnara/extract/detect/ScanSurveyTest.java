package com.onnara.extract.detect;

import com.onnara.extract.TestFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 전체 문서 대상 스캔 판별 집계({@link ScanSurvey})의 samples/ 기반 테스트. */
class ScanSurveyTest {

    /** 스캔본 1건 + 네이티브 2건을 섞어 집계와 스캔본 목록이 맞는지 검증한다. */
    @Test
    void countsScannedAndNativeSeparately() {
        Path scanned = TestFixtures.sample("ocr 테스트용.hwpx");
        Path nativeHwp = TestFixtures.sample("공유수면 점용사용 허가 고시문.hwp");
        Path nativeHml = TestFixtures.sample("8_공유수면 점용 사용 변경허가 고시(한국해양소년단).hml");

        ScanSurvey survey = ScanSurvey.of(List.of(scanned, nativeHwp, nativeHml));

        assertEquals(3, survey.total());
        assertEquals(1, survey.scanned());
        assertEquals(2, survey.nativeCount());
        assertEquals(0, survey.failed());
        assertEquals(3, survey.succeeded());
        assertEquals(1.0 / 3, survey.scannedRatio(), 1e-9);
        assertEquals(List.of(scanned), survey.scannedFiles());
    }

    /** 판별 실패는 네이티브가 아니라 failed로 세고, 비율 분모에서도 빠져야 한다. */
    @Test
    void detectionFailureIsCountedApartFromNative() {
        Path scanned = TestFixtures.sample("ocr 테스트용.hwpx");
        Path unsupported = Path.of("samples", "존재하지-않는-형식.txt");

        ScanSurvey survey = ScanSurvey.of(List.of(scanned, unsupported));

        assertEquals(2, survey.total());
        assertEquals(1, survey.scanned());
        assertEquals(0, survey.nativeCount());
        assertEquals(1, survey.failed());
        assertEquals(1, survey.succeeded());
        // 실패를 네이티브로 셌다면 50%가 된다 — 분모는 판별 성공분 1건이어야 한다
        assertEquals(1.0, survey.scannedRatio(), 1e-9);

        ScanSurvey.Entry failedEntry = survey.entries().get(1);
        assertEquals(ScanSurvey.Status.FAILED, failedEntry.status());
        assertNotNull(failedEntry.error(), "실패 사유가 남아야 함");
        assertTrue(failedEntry.error().contains("txt"), "사유에 확장자가 드러나야 함: " + failedEntry.error());
        assertNotNull(failedEntry.kind(), "실패 갈래가 채워져야 함");
    }

    /**
     * 실패는 갈래별로 세어야 대응이 갈린다 — "판별 실패 N개"만으로는 손쓸 수 있는 게 없다.
     * 예시 파일도 함께 들어 사유를 곧바로 확인할 수 있어야 한다.
     */
    @Test
    void groupsFailuresByKindWithSamples() {
        Path nativeHwp = TestFixtures.sample("공유수면 점용사용 허가 고시문.hwp");
        Path unsupported = Path.of("samples", "존재하지-않는-형식.txt");
        Path missing = Path.of("samples", "없는-파일.hwp");

        ScanSurvey survey = ScanSurvey.of(List.of(nativeHwp, unsupported, missing));

        assertEquals(2, survey.failed());
        assertEquals(2, survey.failures().size());

        List<ScanSurvey.FailureStat> stats = survey.byFailureKind(3);
        assertEquals(2, stats.size());
        assertEquals(2, stats.stream().mapToInt(ScanSurvey.FailureStat::count).sum());
        for (ScanSurvey.FailureStat stat : stats) {
            assertFalse(stat.samples().isEmpty(), "갈래마다 예시 파일이 있어야 함: " + stat.kind());
            assertFalse(stat.kind().description().isBlank(), "갈래에 설명이 있어야 함");
        }
        // 성공 건은 실패 집계에 섞이지 않는다
        assertTrue(stats.stream().flatMap(s -> s.samples().stream()).noneMatch(nativeHwp::equals));
    }

    /** 예시 수 상한을 지켜야 집계 출력이 파일 목록으로 번지지 않는다. */
    @Test
    void limitsNumberOfSamplesPerKind() {
        List<Path> broken = List.of(
                Path.of("samples", "a.txt"), Path.of("samples", "b.txt"),
                Path.of("samples", "c.txt"), Path.of("samples", "d.txt"));

        ScanSurvey survey = ScanSurvey.of(broken);
        List<ScanSurvey.FailureStat> stats = survey.byFailureKind(2);

        assertEquals(1, stats.size());
        assertEquals(4, stats.get(0).count());
        assertEquals(2, stats.get(0).samples().size());
    }

    /** 확장자별 소계가 확장자 오름차순으로, 상태별로 나뉘어 나오는지 검증한다. */
    @Test
    void breaksDownByExtensionInAlphabeticalOrder() {
        Path scannedHwpx = TestFixtures.sample("ocr 테스트용.hwpx");
        Path nativeHwpx = TestFixtures.sample("방치선박 제거공고(군산-2).hwpx");
        Path nativeHwp = TestFixtures.sample("공유수면 점용사용 허가 고시문.hwp");

        ScanSurvey survey = ScanSurvey.of(List.of(scannedHwpx, nativeHwpx, nativeHwp));
        List<ScanSurvey.ExtStat> stats = survey.byExtension();

        assertEquals(List.of("hwp", "hwpx"), stats.stream().map(ScanSurvey.ExtStat::ext).toList());
        assertEquals(new ScanSurvey.ExtStat("hwp", 1, 0, 1, 0), stats.get(0));
        assertEquals(new ScanSurvey.ExtStat("hwpx", 2, 1, 1, 0), stats.get(1));
    }

    /** 빈 목록에서도 0으로 나누지 않는다. */
    @Test
    void emptyInputYieldsZeroRatio() {
        ScanSurvey survey = ScanSurvey.of(List.of());

        assertEquals(0, survey.total());
        assertEquals(0.0, survey.scannedRatio(), 1e-9);
        assertTrue(survey.byExtension().isEmpty());
    }
}
