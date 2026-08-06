package com.onnara.extract.common;

import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 예외를 사람이 읽을 사유 문장으로 바꾼다 — 배치 로그({@code [실패] <파일>: <사유>})의 단일 출처.
 *
 * <p>파이프라인은 원인 예외를 계층마다 감싼다(예: hwplib의 파싱 오류 →
 * {@code IOException("HWP 파일을 읽을 수 없습니다: …")} → {@code UncheckedIOException("HWP 스캔 판별 실패: …")}).
 * 로그가 맨 바깥 예외의 {@code getMessage()}만 찍으면 "읽지 못했다"는 사실만 남고 <b>왜</b>가 사라져,
 * 실패 건을 사후에 분류할 수 없다. 그래서 원인 체인을 끝까지 이어 붙인다.
 *
 * <p>같은 메시지가 겹쳐 나오는 래핑(원인 메시지를 그대로 물고 다시 감싸는 흔한 패턴)은
 * 한 번만 남긴다 — 같은 문장이 세 번 반복되면 읽는 사람이 원인을 못 찾는다.
 */
public final class Errors {

    /** 원인 체인을 따라갈 최대 깊이 — 병적으로 긴 체인이 로그 한 줄을 통째로 먹지 않게 한다. */
    private static final int MAX_DEPTH = 8;

    /** 인스턴스화 방지 — 정적 서술 함수만 제공하는 유틸리티 클래스. */
    private Errors() {
    }

    /**
     * 예외와 그 원인 체인을 한 줄 사유로 만든다.
     *
     * <p>예: {@code "HWPX 스캔 판별 실패: a.hwpx [원인: java.util.zip.ZipException: encrypted ZIP entry not supported]"}
     *
     * @param t 서술할 예외(null이면 {@code "알 수 없는 오류"})
     */
    public static String describe(Throwable t) {
        if (t == null) {
            return "알 수 없는 오류";
        }
        StringBuilder out = new StringBuilder(labelOf(t));
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(t);

        Throwable cause = t.getCause();
        int depth = 0;
        boolean first = true;
        while (cause != null && depth++ < MAX_DEPTH && seen.add(cause)) {
            String label = labelOf(cause);
            // 래퍼가 원인 메시지를 그대로 물고 있으면 같은 문장을 두 번 찍지 않는다
            if (out.indexOf(label) < 0) {
                out.append(first ? " [원인: " : " ← ").append(label);
                first = false;
            }
            cause = cause.getCause();
        }
        if (!first) {
            out.append(']');
        }
        return out.toString();
    }

    /** 원인 체인의 맨 끝 예외 — 실패 분류는 래퍼가 아니라 이것을 근거로 삼는다. */
    public static Throwable rootCause(Throwable t) {
        if (t == null) {
            return null;
        }
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(t);
        Throwable current = t;
        while (current.getCause() != null && seen.add(current.getCause())) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 예외 하나를 "타입: 메시지"로 적는다. 메시지가 없으면 타입 이름만 남긴다
     * (메시지 없는 {@code NullPointerException}이 빈 문자열로 찍히면 아무 단서가 안 된다).
     */
    private static String labelOf(Throwable t) {
        String message = t.getMessage();
        String type = t.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }
}
