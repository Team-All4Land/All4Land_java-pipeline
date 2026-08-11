package com.onnara.extract.detect;

import com.onnara.extract.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 잠금 판정({@link EncryptionProbe}) 단위 테스트.
 *
 * <p>가장 중요한 축은 <b>오탐이 없다</b>는 것이다. 잠기지 않은 문서를 배포용이라고 하면
 * 고칠 수 없는 파일을 계속 재저장해 보게 만든다 — 그래서 기존 픽스처 전건을 NONE으로 검증한다.
 */
class EncryptionProbeTest {

    /** 배포용 HWP는 FileHeader 속성 비트만으로 확정된다 — 본문을 열지 않는다. */
    @Test
    void detectsDistributionHwp() {
        Path file = TestFixtures.sample("배포용 공유수면 점용사용허가 고시(광양시).hwp");

        assertEquals(EncryptionProbe.Lock.DISTRIBUTION, EncryptionProbe.probe(file));
    }

    /** 배포용 HWPX는 매니페스트의 encryption-data + content.hpf의 distribution 속성으로 가른다. */
    @Test
    void detectsDistributionHwpx() {
        Path file = TestFixtures.sample("배포용 공유수면 점용 고시.hwpx");

        assertEquals(EncryptionProbe.Lock.DISTRIBUTION, EncryptionProbe.probe(file));
    }

    /**
     * 잠기지 않은 실제 고시문은 전부 NONE이어야 한다.
     *
     * <p>여기서 하나라도 DISTRIBUTION이 나오면 멀쩡한 문서가 통째로 추출 대상에서 빠진다 —
     * 이 프로브가 낼 수 있는 가장 나쁜 결과다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "공유수면 점용사용 허가 고시문.hwp",
            "공유수면 점용사용 승인사항 고시.hwp",
            "방치선박 제거공고(군산-2).hwpx",
            "방치선박 제거공고(부안-11).hwpx",
            "ocr 테스트용.hwpx",
            "한글3.0 공유수면 고시(동해시).hwp",
            "5_고시양식 (태항조선, 변경).hml"})
    void reportsNoLockForOrdinaryDocuments(String name) {
        Path file = TestFixtures.sample(name);

        assertEquals(EncryptionProbe.Lock.NONE, EncryptionProbe.probe(file),
                name + "은 잠긴 문서가 아니다");
    }

    /** 판정에 실패해도 예외를 던지지 않는다 — 이 프로브가 진짜 원인을 가리면 안 된다. */
    @Test
    void neverThrowsOnGarbage(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("깨진파일.hwp");
        Files.write(file, "이건 아무 형식도 아니다".getBytes(StandardCharsets.UTF_8));

        assertEquals(EncryptionProbe.Lock.NONE, EncryptionProbe.probe(file));
        assertEquals(EncryptionProbe.Lock.NONE, EncryptionProbe.probe(dir.resolve("없는파일.hwp")));
    }

    /** 잠긴 문서는 안내문이 담긴 예외로 즉시 걸러진다 — 원인 체인 첫 문장이 읽혀야 한다. */
    @Test
    void requireUnlockedExplainsHowToFixIt() {
        Path file = TestFixtures.sample("배포용 공유수면 점용 고시.hwpx");

        IOException e = assertThrows(IOException.class, () -> EncryptionProbe.requireUnlocked(file));

        assertTrue(e.getMessage().contains("배포용 문서"), e.getMessage());
        assertTrue(e.getMessage().contains("일반 문서로 저장"), e.getMessage());
    }

    /** 잠기지 않은 문서는 그냥 통과시킨다. */
    @Test
    void requireUnlockedPassesOrdinaryDocuments() {
        Path file = TestFixtures.sample("공유수면 점용사용 허가 고시문.hwp");

        assertDoesNotThrow(() -> EncryptionProbe.requireUnlocked(file));
    }
}
