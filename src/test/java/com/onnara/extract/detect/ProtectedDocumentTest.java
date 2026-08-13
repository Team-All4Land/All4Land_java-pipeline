package com.onnara.extract.detect;

import com.onnara.extract.TestFixtures;
import com.onnara.extract.common.Mapper;
import com.onnara.extract.common.model.NoticeRecord;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawTable;
import com.onnara.extract.engine.Extractor;
import com.onnara.extract.engine.ExtractorRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 보호된 문서가 <b>갈래별로</b> 갈리는지, 그리고 열 수 있는 것은 계속 열리는지 확인한다.
 *
 * <p>"열리지 않는다"를 한 갈래로 뭉쳐 세면 배치 결과를 받고도 무엇부터 손대야 할지 알 수 없다.
 * 배포용은 원래 읽히므로 <b>실패로 돌아가지 않는 것</b>까지 여기서 고정한다.
 */
class ProtectedDocumentTest {

    /** 배포용 HWP 픽스처 2건 — 둘 다 정상 추출돼야 한다. */
    private static List<Path> distributionSamples() {
        return List.of(
                TestFixtures.sample("배포용 - 공유수면 실시계획 신고 고시(영광군).hwp"),
                TestFixtures.sample("배포용 - 공유수면 공사완료신고 수리 고시(영광군).hwp"));
    }

    /** ODF 암호화 HWPX 픽스처. */
    private static Path encryptedHwpx() {
        return TestFixtures.sample("암호화 - 공유수면 고시.hwpx");
    }

    /**
     * <b>배포용은 실패가 아니다.</b> 열쇠가 파일 안에 있어 hwplib이 ViewText를 복호화한다.
     * 이 테스트가 깨지면 "보호 문서를 가른다"는 변경이 읽히던 문서까지 막아 세운 것이다.
     */
    @Test
    void distributionDocumentsStillExtract() throws IOException {
        for (Path file : distributionSamples()) {
            Extractor extractor = ExtractorRegistry.forFile(file);
            RawDocument raw = extractor.extractRaw(file);

            assertEquals("hwplib", extractor.engineName(), file.toString());
            assertFalse(raw.getContent().isEmpty(), "본문이 비었습니다: " + file);
            assertTrue(raw.getContent().stream().anyMatch(RawTable.class::isInstance),
                    "표를 못 읽었습니다: " + file);
        }
    }

    /** 배포용 문서가 표준 필드까지 채워지는지 — 추출만 되고 매핑이 비면 의미가 없다. */
    @Test
    void distributionDocumentFillsStandardFields() throws IOException {
        Path file = TestFixtures.sample("배포용 - 공유수면 공사완료신고 수리 고시(영광군).hwp");

        RawDocument raw = new com.onnara.extract.engine.hwp.HwplibExtractor().extractRaw(file);
        NoticeRecord record = Mapper.mapToSchema(raw, "hwplib").getRecords().get(0);

        assertEquals("영광군", record.agency());
        assertTrue(record.noticeNo().contains("2025"), record.noticeNo());
        assertTrue(record.title().contains("공유수면"), record.title());
    }

    /** 배포용 문서는 스캔본이 아니다 — 판별이 예외 없이 네이티브로 떨어져야 한다. */
    @Test
    void distributionDocumentsAreDetectedAsNative() {
        for (Path file : distributionSamples()) {
            assertFalse(DetectorRegistry.isScanned(file), file.toString());
        }
    }

    /**
     * 암호화된 HWPX는 본문 XML을 파싱해 보기 <b>전에</b> 판별기가 막는다.
     *
     * <p>예전에는 암호화된 바이트가 그대로 XML 파서로 들어가 사유가
     * {@code Invalid byte 2 of 2-byte UTF-8 sequence}로 남았다 — 받는 사람이 손쓸 수 없는 문장이다.
     */
    @Test
    void encryptedHwpxFailsWithAPurposefulMessage() {
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> DetectorRegistry.isScanned(encryptedHwpx()));

        String message = com.onnara.extract.common.Errors.describe(error);
        assertTrue(message.contains("암호화"), message);
        assertFalse(message.contains("Invalid byte"), "XML 파서까지 흘러갔습니다: " + message);
    }

    /**
     * 갈래는 {@code XML_PARSE}가 아니라 {@link FailureKind#PASSWORD_PROTECTED}여야 한다.
     * 암호화를 XML 손상으로 세면 "원본을 다시 받아라"라는 틀린 대응을 안내하게 된다.
     */
    @Test
    void encryptedHwpxIsClassifiedAsPasswordProtected() {
        Path file = encryptedHwpx();
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> DetectorRegistry.isScanned(file));

        FailureClassifier.Result result = FailureClassifier.classify(file, error);

        assertEquals(FailureKind.PASSWORD_PROTECTED, result.kind());
        assertTrue(result.detail().contains("aes256-cbc"), result.detail());
    }

    /** 배포용 문서가 실패했다면 암호가 아니라 우리 쪽 한계다 — 갈래가 그렇게 말해야 한다. */
    @Test
    void failingDistributionDocumentIsNotCalledPasswordProtected() {
        Path file = TestFixtures.sample("배포용 - 공유수면 실시계획 신고 고시(영광군).hwp");

        FailureClassifier.Result result = FailureClassifier.classify(
                file, new IOException("HWP 문단 텍스트 추출 실패"));

        assertEquals(FailureKind.DISTRIBUTION_UNSUPPORTED, result.kind());
        assertTrue(result.detail().contains("배포용"), result.detail());
    }

    /**
     * 끝부분이 잘려 실패한 배포용 문서는 <b>할 일까지</b> 말해 준다.
     *
     * <p>이 광양시 건은 파일도 우리 파서도 멀쩡하다 — hwplib이 복호화 마지막 블록을 잃어 본문 끝이
     * 잘릴 뿐이라, 한글에서 다시 저장하면 그대로 읽힌다. "개별 확인이 필요합니다"로 뭉뚱그리면
     * 받는 사람이 원인을 처음부터 다시 파야 한다.
     */
    @Test
    void truncatedDistributionDocumentTellsHowToFixIt() {
        Path file = TestFixtures.sample("배포용 공유수면 점용사용허가 고시(광양시).hwp");

        FailureClassifier.Result result = FailureClassifier.classify(
                file, new IOException("java.lang.Exception: This is not paragraph."));

        assertEquals(FailureKind.DISTRIBUTION_TRUNCATED, result.kind());
        assertTrue(result.detail().contains("다른 이름으로 저장"), result.detail());
    }

    /**
     * <b>잘리지 않는 배포용은 이 갈래로 새지 않는다.</b> 영광군 2건은 마지막 블록을 잃어도 본문이
     * 온전해 실제로 잘 읽힌다 — 이들까지 "다시 저장하세요"라고 하면, 고칠 것도 없는 문서를 붙들고
     * 재저장을 반복하게 만든다.
     */
    @Test
    void intactDistributionDocumentsAreNotReportedAsTruncated() {
        for (Path file : distributionSamples()) {
            assertFalse(DistributionTail.truncatesBody(file), file.toString());
        }
    }
}
