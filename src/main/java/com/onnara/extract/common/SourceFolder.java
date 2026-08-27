package com.onnara.extract.common;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 기관과 게시판 구분을 읽어낸다. 입력은 두 가지인데 <b>형식이 같아 파서는 하나다</b>:
 * 크롤러 수집 결과 엑셀 검증요약 시트의 {@code 번호}+{@code 기관명}을 {@code _}로 이은 것과,
 * 크롤 산출물이 아닌 파일이 들어 있는 입력 폴더의 이름이다.
 *
 * <p>한 기관이 게시판을 여럿 운영하면 하위번호로 갈린다:
 * <pre>
 * 1_인천지방해양수산청            → (1,    인천지방해양수산청, mof,   게시중)
 * 12_2_목포시청_지난자료          → (12/2, 목포시청,           local, 게시완료)
 * 57_경상남도_고성군청            → (57,   경상남도 고성군청,  local, 게시중)
 * 72_1_전자관보_농림축산식품부    → (72/1, 농림축산식품부,     gazette, 게시중)
 * </pre>
 *
 * <p>이 이름이 "어느 기관 게시판을 긁었는가"에 대한 <b>유일한</b> 근거다 — 첨부파일 안에는
 * 없다. 본문에서 읽은 기관명은 발령 주체이지 수집처가 아니라서 서로 다를 수 있다
 * ({@code OS_ATCH_FILE_DTL.BODY_AGNCY_NM}).
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

    /** 전자관보를 경유해 수집한 폴더의 이름 앞에 붙는 조각. 수집결과 시트의 지자체명이기도 하다. */
    private static final String GAZETTE_PREFIX = "전자관보";

    /**
     * 전자관보 게시물의 비고에서 발령 기관을 읽는 자리 —
     * {@code 웹사이트에서 제목이 "농림축산식품부고시"로 시작함}.
     *
     * <p>따옴표 안에서 꼬리의 "고시"를 뗀 것이 기관명이다. 접두어("웹사이트에서 제목이")를
     * 통째로 못박지 않는 이유는 그 문구가 사람이 적은 메모라 언제든 다듬어질 수 있어서다 —
     * 실제로 고정된 것은 <b>따옴표에 싸인 {기관명}고시</b> 부분뿐이다.
     */
    private static final Pattern GAZETTE_REMARK = Pattern.compile("\"([^\"]+?)고시\"");

    // 아래 두 묶음은 표준코드 CD_BBS_STTS·CD_INSTT_KND의 값이다(db/standard_terms.json).
    // 폴더명을 읽는 정규식은 한글 그대로 두고 판정 결과만 코드로 옮긴다 — 정규식이 보는 것은
    // 사람이 지은 폴더 이름이지 코드가 아니다.

    /** 게시상태코드 — 게시 중. */
    public static final String BBS_STTS_OPEN = "POST";

    /** 게시상태코드 — 게시 완료(지난·이전·완료 공고). */
    public static final String BBS_STTS_CLOSED = "CLSD";

    /** 기관종류코드 — 지방해양수산청. */
    public static final String INSTT_KND_MOF = "MOF";

    /** 기관종류코드 — 지자체. */
    public static final String INSTT_KND_LOCAL = "LOCL";

    /** 기관종류코드 — 중앙행정기관. */
    public static final String INSTT_KND_CENTRAL = "CNTL";

    /** 기관종류코드 — 전자관보 경유 수집. 기관 자체 게시판({@link #INSTT_KND_CENTRAL})과 구분한다. */
    public static final String INSTT_KND_GAZETTE = "GZT";

    /**
     * 파싱 결과.
     *
     * @param folderNo  폴더명 앞 번호. 기관번호의 후보이지 확정값이 아니다
     * @param subNo     하위번호. 없으면 0
     * @param agncyNm   기관명. 언더스코어는 공백으로 잇고 게시판 꼬리표·전자관보 접두는 뗀다
     * @param kndCd  mof / local / central / gazette
     * @param boardCd {@link #BBS_STTS_OPEN} 또는 {@link #BBS_STTS_CLOSED}
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
                kind = INSTT_KND_GAZETTE;
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
     * 전자관보 게시물의 비고에서 발령 기관명을 읽는다.
     *
     * <p>전자관보만 이 손질이 필요하다. 다른 기관은 수집결과 시트의 지자체명이 곧 검증요약의
     * 기관명이지만, 전자관보 게시물은 <b>지자체명이 전부 "전자관보" 한 값</b>이라 검증요약의
     * 두 줄({@code 72_1 전자관보_농림축산식품부} · {@code 72_2 전자관보_새만금개발청}) 중
     * 어느 쪽인지 가릴 수 없다. 게시물 제목도 근거가 못 된다 — 08.07 산출물의 159건이 모두
     * "공유수면 점용·사용…"으로 시작하고, 비고가 말하는 접두어는 <b>웹사이트에 걸린 제목</b>의
     * 것이라 엑셀 제목 칸에는 남아 있지 않다. 비고가 유일한 근거다.
     *
     * <p>돌려주는 이름 앞에 {@code 전자관보_}를 붙이면 검증요약의 기관명이 되고, 그것을
     * {@link #parse}에 먹이면 다른 기관과 똑같은 경로로 기관번호·종류가 정해진다.
     *
     * <pre>
     * 웹사이트에서 제목이 "농림축산식품부고시"로 시작함  → 농림축산식품부
     * 웹사이트에서 제목이 "새만금개발청고시"로 시작함    → 새만금개발청
     * </pre>
     *
     * @param remark 수집결과 시트의 비고 칸
     * @return 읽어내지 못하면 {@link Optional#empty()} — 그때는 기관을 붙이지 않는다.
     *         엉뚱한 기관에 밀어 넣으면 기관별 집계가 조용히 오염되고, 그 오염은
     *         전자관보 두 기관 사이에서만 일어나 밖에서는 보이지 않는다
     */
    public static Optional<String> gazetteAgencyOf(String remark) {
        if (remark == null) {
            return Optional.empty();
        }
        Matcher m = GAZETTE_REMARK.matcher(remark);
        if (!m.find()) {
            return Optional.empty();
        }
        String agency = m.group(1).trim();
        return agency.isEmpty() ? Optional.empty() : Optional.of(agency);
    }

    /**
     * 수집결과 시트의 지자체명이 전자관보인지 — 비고를 봐야 하는 행을 가린다.
     *
     * @param agencyCell 수집결과 시트의 지자체명 칸
     */
    public static boolean isGazette(String agencyCell) {
        return agencyCell != null && agencyCell.trim().equals(GAZETTE_PREFIX);
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
            return BBS_STTS_OPEN;
        }
        String last = parts[parts.length - 1].trim();
        return CLOSED_MARK.matcher(last).matches() ? BBS_STTS_CLOSED : BBS_STTS_OPEN;
    }

    /**
     * 기관명 어미로 종류를 정한다.
     *
     * <p>번호 구간(1~11은 해양수산청, 70~72는 중앙)으로 가르지 않는다 — 크롤 순서가 바뀌면
     * 조용히 어긋나고, 그 어긋남은 집계를 다 뽑고 나서야 드러난다.
     */
    private static String kindOf(String name) {
        if (name.endsWith("지방해양수산청")) {
            return INSTT_KND_MOF;
        }
        if (name.endsWith("시청") || name.endsWith("군청") || name.endsWith("구청")
                || name.endsWith("도청") || name.endsWith("시") || name.endsWith("군")
                || name.endsWith("구") || name.endsWith("도")) {
            return INSTT_KND_LOCAL;
        }
        return INSTT_KND_CENTRAL;
    }
}
