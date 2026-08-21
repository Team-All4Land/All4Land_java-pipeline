package com.onnara.extract.common;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 첨부파일명에서 게시물 순번과 첨부 순번을 읽어낸다.
 *
 * <p>크롤러는 <b>두 가지 형태</b>를 섞어 쓴다 — 첨부가 하나뿐인 게시물에는 첨부순번을 붙이지 않는다:
 * <pre>
 * 1000_해동마린 공유수면 점사용 변경(기간연장)허가 고시문.hwp  → (1000, 1)  첨부 1건
 * 152_1_공유수면 점용·사용허가 취소 고시.hml                 → (152,  1)  첨부 2건 중 첫째
 * 152_2_공유수면 점용·사용허가 취소 표지.hwpx                → (152,  2)
 * </pre>
 *
 * <p>순번은 기관별로 다시 매기는 번호가 아니라 <b>하나의 크롤 시퀀스</b>다 — 기관마다 연속 블록을
 * 차지하고 기관 간 충돌이 없다(인천 1~1108, 군산 1109~1660, 평택 1661~1759 …).
 * 그래서 게시물 키는 {@code (기관, 순번)}이 아니라 순번 하나로 충분하다.
 *
 * <p>패턴이 맞지 않는 파일(수동 수집분, {@code samples/})은 첨부 1개짜리 단독 공고로 본다.
 * 이때 순번은 <b>음수</b>로 발급한다 — 파일명 해시를 쓰면 실제 크롤 순번과 충돌할 수 있고,
 * 양수 시퀀스를 쓰면 나중에 크롤 데이터를 넣을 때 겹친다. 음수 구간은 크롤러가 절대
 * 쓰지 않으므로 두 출처가 한 DB에서 안전하게 공존한다.
 */
public final class SourceFileName {

    /**
     * {순번}_{첨부순번}_{제목} — 첨부가 여러 건인 게시물에만 붙는 형태.
     *
     * <p>첨부순번을 <b>2자리로 막는다</b>. {@code \\d+}로 열어 두면
     * {@code 1450_20130409174438859_공유수면_점용사용_변경허가_고시(포스코).hwp}처럼 제목이
     * 타임스탬프로 시작하는 파일(전수 표본 134건)이 첨부순번 20130409174438859로 읽힌다.
     * 실제 첨부순번은 1~4뿐이다.
     */
    private static final Pattern MULTI = Pattern.compile("^(\\d{1,9})_(\\d{1,2})_(.+)$");

    /** {순번}_{제목} — 첨부가 1건이라 크롤러가 첨부순번을 생략한 형태. 전수 표본의 다수다. */
    private static final Pattern SINGLE = Pattern.compile("^(\\d{1,9})_(.+)$");

    /** 패턴에 맞지 않는 파일에 줄 순번 발급기 — -1부터 하나씩 내려간다. */
    private static final AtomicInteger FALLBACK_SEQUENCE = new AtomicInteger(0);

    /**
     * 파싱 결과.
     *
     * @param notiSn 게시물 순번. 크롤 파일이면 파일명 앞자리, 아니면 음수
     * @param atchSn 첨부 순번. 파일명에 없으면 1
     * @param crawled  파일명에서 실제로 읽어낸 값인지 — false면 폴백으로 발급한 값이다
     */
    public record Parsed(int notiSn, int atchSn, boolean crawled) {
    }

    /** 인스턴스화 방지 — 정적 파싱 함수만 제공하는 유틸리티 클래스. */
    private SourceFileName() {
    }

    /**
     * 파일명에서 순번·첨부순번을 읽는다. 패턴이 아니면 새 음수 순번을 발급한다.
     *
     * <p>순수 함수로 둔다 — 같은 폴더의 형제 파일을 봐야 첨부순번을 정할 수 있게 만들면
     * {@code map}(raw JSON만 읽는다)과 {@code Mapper}(파일명만 받는다)가 함께 깨진다.
     *
     * @param fileName 확장자를 포함한 파일명(경로 아님)
     */
    public static Parsed parse(String fileName) {
        return peek(fileName).orElseGet(
                () -> new Parsed(FALLBACK_SEQUENCE.decrementAndGet(), 1, false));
    }

    /**
     * 파일명에서 읽어낼 수 있는 순번만 돌려준다 — <b>폴백 순번을 발급하지 않는다</b>.
     *
     * <p>적재 전에 키 중복을 미리 훑는 것처럼 "읽어만 보는" 용도다. 그 자리에서
     * {@link #parse}를 쓰면 크롤 패턴이 아닌 파일마다 음수 순번이 한 개씩 소모돼,
     * 정작 매핑 단계가 발급받는 번호와 어긋난다.
     *
     * @return 크롤 패턴이 아니면 {@link Optional#empty()}
     */
    public static Optional<Parsed> peek(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        String stem = stripExtension(fileName);
        Parsed multi = match(MULTI, stem, true);
        if (multi != null) {
            return Optional.of(multi);
        }
        return Optional.ofNullable(match(SINGLE, stem, false));
    }

    /**
     * 패턴 하나를 맞춰 본다. 맞지 않거나 순번이 int를 넘으면 null(다음 패턴 또는 폴백으로).
     *
     * @param withAttachNo 두 번째 그룹이 첨부순번인지 — false면 제목이므로 첨부순번은 1이다
     */
    private static Parsed match(Pattern pattern, String stem, boolean withAttachNo) {
        Matcher m = pattern.matcher(stem);
        if (!m.matches()) {
            return null;
        }
        try {
            int notiSn = Integer.parseInt(m.group(1));
            int atchSn = withAttachNo ? Integer.parseInt(m.group(2)) : 1;
            return new Parsed(notiSn, atchSn, true);
        } catch (NumberFormatException e) {
            // 9자리를 넘겨 int를 벗어나는 순번은 크롤 산출물이 아니다 — 폴백으로 넘어간다
            return null;
        }
    }

    /** 확장자를 뗀 파일명. 확장자가 없으면 원형 그대로. */
    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }
}
