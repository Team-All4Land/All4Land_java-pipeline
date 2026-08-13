package com.onnara.extract.common;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 첨부파일명에서 게시물 순번과 첨부 순번을 읽어낸다.
 *
 * <p>크롤러가 {@code {순번}_{첨부순번}_{제목}.{확장자}} 형태로 저장한다:
 * <pre>
 * 6034_1_5 공유수면 점용 사용 허가 고시문(소라면 사곡리 1291).hwp  → (6034, 1)
 * 9999_2_실시계획 승인증(신고확인증)2022-80.hwp                  → (9999, 2)
 * </pre>
 *
 * <p>순번은 기관별로 다시 매기는 번호가 아니라 <b>하나의 크롤 시퀀스</b>다 — 전수 표본
 * 2,528건에서 83~21,751 범위에 기관 간 충돌이 0건이고, 기관마다 연속 블록을 차지한다.
 * 그래서 게시물 키는 {@code (기관, 순번)}이 아니라 순번 하나로 충분하다.
 *
 * <p>패턴이 맞지 않는 파일(수동 수집분, {@code samples/})은 첨부 1개짜리 단독 공고로 본다.
 * 이때 순번은 <b>음수</b>로 발급한다 — 파일명 해시를 쓰면 실제 크롤 순번과 충돌할 수 있고,
 * 양수 시퀀스를 쓰면 나중에 크롤 데이터를 넣을 때 겹친다. 음수 구간은 크롤러가 절대
 * 쓰지 않으므로 두 출처가 한 DB에서 안전하게 공존한다.
 */
public final class SourceFileName {

    /** {순번}_{첨부순번}_ 접두. 뒤에 제목이 붙지 않는 경우도 허용한다. */
    private static final Pattern CRAWLED = Pattern.compile("^(\\d{1,9})_(\\d{1,4})(?:_.*)?$");

    /** 패턴에 맞지 않는 파일에 줄 순번 발급기 — -1부터 하나씩 내려간다. */
    private static final AtomicInteger FALLBACK_SEQUENCE = new AtomicInteger(0);

    /**
     * 파싱 결과.
     *
     * @param noticeNo 게시물 순번. 크롤 파일이면 파일명 앞자리, 아니면 음수
     * @param attachNo 첨부 순번. 크롤 파일이 아니면 항상 1
     * @param crawled  파일명에서 실제로 읽어낸 값인지 — false면 폴백으로 발급한 값이다
     */
    public record Parsed(int noticeNo, int attachNo, boolean crawled) {
    }

    /** 인스턴스화 방지 — 정적 파싱 함수만 제공하는 유틸리티 클래스. */
    private SourceFileName() {
    }

    /**
     * 파일명에서 순번·첨부순번을 읽는다. 패턴이 아니면 새 음수 순번을 발급한다.
     *
     * @param fileName 확장자를 포함한 파일명(경로 아님)
     */
    public static Parsed parse(String fileName) {
        if (fileName != null) {
            Matcher m = CRAWLED.matcher(stripExtension(fileName));
            if (m.matches()) {
                try {
                    return new Parsed(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), true);
                } catch (NumberFormatException ignored) {
                    // 9자리를 넘겨 int를 벗어나는 순번은 크롤 산출물이 아니다 — 폴백으로 넘어간다
                }
            }
        }
        return new Parsed(FALLBACK_SEQUENCE.decrementAndGet(), 1, false);
    }

    /** 확장자를 뗀 파일명. 확장자가 없으면 원형 그대로. */
    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }
}
