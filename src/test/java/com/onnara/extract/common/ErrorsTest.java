package com.onnara.extract.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 예외 서술기({@link Errors}) 단위 테스트. */
class ErrorsTest {

    /** 래퍼만 찍으면 사라지던 원인이 사유 문장에 남아야 한다 — 이 계층이 존재하는 이유다. */
    @Test
    void keepsRootCauseThatWrapperMessageHides() {
        Throwable root = new java.util.zip.ZipException("encrypted ZIP entry not supported");
        Throwable wrapped = new UncheckedIOException("HWPX 스캔 판별 실패: a.hwpx", new IOException(root));

        String described = Errors.describe(wrapped);

        assertTrue(described.startsWith("UncheckedIOException: HWPX 스캔 판별 실패: a.hwpx"), described);
        assertTrue(described.contains("encrypted ZIP entry not supported"), described);
    }

    /** 같은 문장을 물고 다시 감싼 래핑은 한 번만 남아야 한다(세 번 반복되면 읽을 수 없다). */
    @Test
    void doesNotRepeatIdenticalWrapperMessages() {
        IOException inner = new IOException("깨진 파일");
        IOException outer = new IOException("IOException: 깨진 파일", inner);

        String described = Errors.describe(outer);

        assertEquals(1, described.split("깨진 파일", -1).length - 1, described);
    }

    /** 메시지가 없는 예외는 타입 이름으로라도 단서를 남겨야 한다. */
    @Test
    void fallsBackToTypeNameWhenMessageIsMissing() {
        assertEquals("NullPointerException", Errors.describe(new NullPointerException()));
    }

    /** null도 조용히 받아 로그 한 줄을 만들어야 한다(로깅 경로가 죽으면 안 된다). */
    @Test
    void describesNullWithoutThrowing() {
        assertEquals("알 수 없는 오류", Errors.describe(null));
    }

    /** 서로를 원인으로 가리키는 예외에도 무한 루프에 빠지지 않아야 한다. */
    @Test
    void survivesCyclicCauseChain() {
        IOException a = new IOException("A");
        IOException b = new IOException("B", a);
        a.initCause(b);

        String described = Errors.describe(b);

        assertTrue(described.contains("B"), described);
        assertTrue(described.contains("A"), described);
    }

    /** 원인 체인의 맨 끝을 집어내야 분류가 래퍼가 아닌 진짜 원인을 근거로 삼는다. */
    @Test
    void findsRootCause() {
        Throwable root = new IllegalStateException("바닥");
        Throwable wrapped = new UncheckedIOException("겉", new IOException(root));

        assertEquals(root, Errors.rootCause(wrapped));
        assertFalse(Errors.rootCause(wrapped) instanceof UncheckedIOException);
    }
}
