package com.onnara.extract.detect;

import com.onnara.extract.TestFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 보호 상태 판별({@link DocProtection})의 단위 테스트 — 실제 배포용·암호화 문서로 검증한다. */
class DocProtectionTest {

    /** 배포용 HWP 픽스처(영광군 공유수면 고시 2건 중 하나). */
    private static Path distribution() {
        return TestFixtures.sample("배포용 - 공유수면 공사완료신고 수리 고시(영광군).hwp");
    }

    /** ODF 표준 암호화가 걸린 HWPX 픽스처. */
    private static Path encryptedHwpx() {
        return TestFixtures.sample("암호화 - 공유수면 고시.hwpx");
    }

    /** 배포용 문서는 배포용으로 판정된다 — 암호 문서와 갈래가 달라야 한다. */
    @Test
    void detectsDistributionHwp() {
        DocProtection protection = DocProtection.of(distribution(), DocFormat.HWP5);

        assertEquals(DocProtection.Kind.DISTRIBUTION, protection.kind());
    }

    /** 보호가 없는 평범한 HWP는 NONE — 근거 없이 보호를 붙이면 정상 파일이 실패로 샌다. */
    @Test
    void reportsNoneForPlainHwp() {
        Path plain = TestFixtures.sample("공유수면 점용사용 허가 고시문.hwp");

        assertEquals(DocProtection.Kind.NONE, DocProtection.of(plain, DocFormat.HWP5).kind());
    }

    /**
     * 암호화된 HWPX는 암호 갈래이고, 사유에 알고리즘·키 유도 방식과 잠긴 항목이 담긴다.
     *
     * <p>키 유도가 PBKDF2라는 것이 "사용자 암호가 있어야 한다"는 근거다 — 이게 메시지에
     * 없으면 받는 사람이 복호화를 시도해 볼지 말지 판단할 수 없다.
     */
    @Test
    void detectsEncryptedHwpxWithAlgorithmDetail() {
        DocProtection protection = DocProtection.of(encryptedHwpx(), DocFormat.HWPX);

        assertEquals(DocProtection.Kind.PASSWORD, protection.kind());
        assertTrue(protection.detail().contains("aes256-cbc"), protection.detail());
        assertTrue(protection.detail().contains("pbkdf2"), protection.detail());
    }

    /**
     * 잠긴 항목 목록에 미리보기까지 들어간다 — 이 문서는 {@code Preview/PrvText.txt}도
     * 암호화돼 있어 "미리보기라도 건지자"가 통하지 않는다는 사실이 사유만 보고 확인돼야 한다.
     */
    @Test
    void listsEncryptedEntriesIncludingPreview() {
        String detail = DocProtection.of(encryptedHwpx(), DocFormat.HWPX).detail();

        assertTrue(detail.contains("Contents/section0.xml"), detail);
        assertTrue(detail.contains("Preview/PrvText.txt"), detail);
    }

    /** 암호화 선언이 없는 HWPX는 NONE. */
    @Test
    void reportsNoneForPlainHwpx() {
        Path plain = TestFixtures.sample("방치선박 제거공고(군산-2).hwpx");

        assertEquals(DocProtection.Kind.NONE, DocProtection.of(plain, DocFormat.HWPX).kind());
    }

    /** 읽을 수 없는 파일에서 예외를 던지지 않는다 — 판별 실패가 분류 자체를 멈추면 안 된다. */
    @Test
    void returnsNoneWhenFileCannotBeRead() {
        Path missing = Path.of("samples", "없는파일.hwp");

        assertEquals(DocProtection.Kind.NONE, DocProtection.of(missing, DocFormat.HWP5).kind());
    }
}
