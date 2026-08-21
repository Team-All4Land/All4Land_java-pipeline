package com.onnara.extract.common;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 입력 폴더명에서 기관과 게시판 구분을 읽어낸다.
 *
 * <p>크롤러는 기관마다 폴더 하나를 만들고 그 안에 첨부파일을 떨어뜨린다. 한 기관이 게시판을
 * 여럿 운영하면 하위번호로 갈린다:
 * <pre>
 * 1_인천지방해양수산청            → (1,    인천지방해양수산청, mof,   게시중)
 * 12_2_목포시청_지난자료          → (12/2, 목포시청,           local, 게시완료)
 * 57_경상남도_고성군청            → (57,   경상남도 고성군청,  local, 게시중)
 * 72_1_전자관보_농림축산식품부    → (72/1, 농림축산식품부,     gazette, 게시중)
 * </pre>
 *
 * <p>이 폴더명이 "어느 기관 게시판을 긁었는가"에 대한 <b>유일한</b> 근거다 — 첨부파일 안에는
 * 없다. 본문에서 읽은 기관명은 발령 주체이지 수집처가 아니라서 서로 다를 수 있다
 * ({@code TB_ATCH_FILE.BODY_AGNCY_NM}).
 *
 * <p>기관 <b>번호</b>는 여기서 정하지 않는다. 한 번호 아래 이름이 갈리는 경우가 있어
 * 폴더 하나만 봐서는 확정할 수 없기 때문이다 — {@link AgencyRegistry}가 배치 전체를 보고 정한다.
 */
public final class SourceFolder {

    /** {번호}_[{하위번호}_]{나머지}. 하위번호는 숫자만으로 이뤄진 두 번째 조각일 때만 인정한다. */
    private static final Pattern NUMBERED = Pattern.compile("^(\\d{1,4})(?:_(\\d{1,3}))?_(.+)$");

    /** 게시가 끝났음을 알리는 꼬리표 — "지난 공고"·"09.01.11 이전 공고"·"완료된" 따위. */
    private static final Pattern CLOSED_MARK = Pattern.compile(".*(지난|이전|완료|종료|만료).*");

    /** 게시 중임을 알리는 꼬리표 — 기관명 뒤에 붙기만 하고 상태를 가르지는 않는다. */
    private static final Pattern OPEN_MARK = Pattern.compile("^(게시중|게시|고시공고|공고|고시|자료).*");

    /** 전자관보를 경유해 수집한 폴더의 이름 앞에 붙는 조각. */
    private static final String GAZETTE_PREFIX = "전자관보";

    /** 게시판 구분 — 게시 중. */
    public static final String BOARD_OPEN = "게시중";

    /** 게시판 구분 — 게시 완료(지난·이전·완료 공고). */
    public static final String BOARD_CLOSED = "게시완료";

    /** 기관 종류 — 지방해양수산청. */
    public static final String KIND_MOF = "mof";

    /** 기관 종류 — 지자체. */
    public static final String KIND_LOCAL = "local";

    /** 기관 종류 — 중앙행정기관. */
    public static final String KIND_CENTRAL = "central";

    /** 기관 종류 — 전자관보 경유 수집. 기관 자체 게시판({@link #KIND_CENTRAL})과 구분한다. */
    public static final String KIND_GAZETTE = "gazette";

    /**
     * 파싱 결과.
     *
     * @param folderNo  폴더명 앞 번호. 기관번호의 후보이지 확정값이 아니다
     * @param subNo     하위번호. 없으면 0
     * @param agncyNm   기관명. 언더스코어는 공백으로 잇고 게시판 꼬리표·전자관보 접두는 뗀다
     * @param kndCd  mof / local / central / gazette
     * @param boardCd {@link #BOARD_OPEN} 또는 {@link #BOARD_CLOSED}
     */
    public record Parsed(int folderNo, int subNo, String agncyNm, String kndCd, String boardCd) {
    }

    /** 인스턴스화 방지 — 정적 파싱 함수만 제공하는 유틸리티 클래스. */
    private SourceFolder() {
    }

    /**
     * 폴더명을 기관·게시판으로 가른다.
     *
     * @param folderName 경로가 아닌 폴더명 한 조각
     * @return 규약에 맞지 않으면 {@link Optional#empty()} — 손으로 만든 폴더까지 기관으로
     *         승격시키면 DB에 유령 기관이 생긴다
     */
    public static Optional<Parsed> parse(String folderName) {
        if (folderName == null) {
            return Optional.empty();
        }
        Matcher m = NUMBERED.matcher(folderName.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        int folderNo;
        try {
            folderNo = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        int subNo = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));

        String[] parts = m.group(3).split("_");
        String board = boardOf(parts);
        int end = parts.length - (isBoardMark(parts) ? 1 : 0);

        StringBuilder name = new StringBuilder();
        String kind = null;
        for (int i = 0; i < end; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                continue;
            }
            // 전자관보는 기관명이 아니라 수집 경로다 — 이름에서 빼고 종류로 옮긴다
            if (i == 0 && part.equals(GAZETTE_PREFIX) && end > 1) {
                kind = KIND_GAZETTE;
                continue;
            }
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(part);
        }
        if (name.length() == 0) {
            return Optional.empty();
        }
        String agency = name.toString();
        return Optional.of(new Parsed(folderNo, subNo, agency,
                kind != null ? kind : kindOf(agency), board));
    }

    /**
     * 마지막 조각이 게시판 꼬리표인지 — 기관명의 일부와 가려야 한다.
     *
     * <p>{@code 57_경상남도_고성군청}의 "고성군청"은 이름이고 {@code 12_2_목포시청_지난자료}의
     * "지난자료"는 꼬리표다. 아는 낱말로만 판정하고, 모르면 이름으로 남긴다 — 반대로 하면
     * 언더스코어가 든 기관명이 조용히 잘린다.
     *
     * <p>조각이 하나뿐이면 절대 꼬리표로 보지 않는다. {@code 70_새만금개발청}이 빈 이름이 된다.
     */
    private static boolean isBoardMark(String[] parts) {
        if (parts.length < 2) {
            return false;
        }
        String last = parts[parts.length - 1].trim();
        return CLOSED_MARK.matcher(last).matches() || OPEN_MARK.matcher(last).matches();
    }

    /** 꼬리표가 "지난·이전·완료"류면 게시완료, 그 밖에는(꼬리표가 없어도) 게시중. */
    private static String boardOf(String[] parts) {
        if (parts.length < 2) {
            return BOARD_OPEN;
        }
        String last = parts[parts.length - 1].trim();
        return CLOSED_MARK.matcher(last).matches() ? BOARD_CLOSED : BOARD_OPEN;
    }

    /**
     * 기관명 어미로 종류를 정한다.
     *
     * <p>번호 구간(1~11은 해양수산청, 70~72는 중앙)으로 가르지 않는다 — 크롤 순서가 바뀌면
     * 조용히 어긋나고, 그 어긋남은 집계를 다 뽑고 나서야 드러난다.
     */
    private static String kindOf(String name) {
        if (name.endsWith("지방해양수산청")) {
            return KIND_MOF;
        }
        if (name.endsWith("시청") || name.endsWith("군청") || name.endsWith("구청")
                || name.endsWith("도청") || name.endsWith("시") || name.endsWith("군")
                || name.endsWith("구") || name.endsWith("도")) {
            return KIND_LOCAL;
        }
        return KIND_CENTRAL;
    }
}
