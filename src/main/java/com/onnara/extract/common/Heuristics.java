package com.onnara.extract.common;

import com.onnara.extract.common.model.RawTable;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 고시문 텍스트 휴리스틱 — 캡션 판별, 제목 추정, 기관/고시번호 분리, 고시자 판별.
 *
 * <p>레거시 TextPatterns의 로직을 raw 계약 모델(RawTable) 기준으로 포팅한 것.
 * Python 버전(hwpx_extractor.py)의 looks_like_caption / guess_title_from_tables와 동일.
 */
public final class Heuristics {

    private static final Pattern[] CAPTION_PATTERNS = new Pattern[]{
            Pattern.compile("^\\s*[<\\[]?\\s*(표|Table)\\s*[\\d一-龥]*\\s*[>\\]]?"),
            Pattern.compile("^\\s*[<\\[]?\\s*(그림|Figure|Fig\\.?|사진)\\s*[\\d一-龥]*\\s*[>\\]]?"),
    };

    // 관공서 "고시문" 서식 패턴: "OO청 고시 제2026-88호" 형태.
    // 자간 벌림("공 고   제  56 호"), 대시 양옆 공백("제 2018 – 82 호"),
    // 연도 없는 일련번호("제 56 호")도 수용한다.
    //
    // 대시 뒤 일련번호를 필수로 두지 않는 이유: 기관이 번호를 비운 채 발행한 고시문이 흔하다
    // ("인천지방해양항만청 고시 제2008- 호"). 숫자를 요구하면 이 줄이 고시번호로 인식되지 않고,
    // 그러면 기관·번호뿐 아니라 "고시번호 다음 줄"이라는 제목 폴백까지 통째로 죽는다.
    private static final String NOTICE_NUMBER_CORE =
            "(?:고\\s*시|공\\s*고)\\s*제\\s*[\\d一-龥]+(?:\\s*[-–—]\\s*[\\d一-龥]*)?\\s*호";

    private static final Pattern NOTICE_NUMBER_PATTERN = Pattern.compile(NOTICE_NUMBER_CORE);

    // "군산지방해양수산청 고시 제2026-47호" → 기관 + 번호 분리 (기관 없는 "공고 제56호"도 허용)
    private static final Pattern AGENCY_AND_NO_PATTERN = Pattern.compile(
            "^\\s*(\\S.*?)??\\s*(" + NOTICE_NUMBER_CORE + ")\\s*$");

    // 허가증·증서 서식은 셀 한 칸에 번호와 제목이 줄바꿈으로 함께 들어간다
    // ("허가번호 제2017-48호\n\n공유수면 점용ㆍ사용 변경허가증"). 고시번호만 보면 잡히지 않는다.
    private static final Pattern DOCUMENT_NUMBER_LINE = Pattern.compile(
            "^\\s*(?:고\\s*시|공\\s*고|허\\s*가\\s*번\\s*호|승\\s*인\\s*번\\s*호|면\\s*허\\s*번\\s*호)"
                    + "\\s*제\\s*[\\d一-龥]+(?:\\s*[-–—]\\s*[\\d一-龥]*)?\\s*호\\s*$");

    // "고 시 문" / "공고문(안)" — 고시·공고 낱말이 번호 줄이 아니라 그 앞 줄에 있는 서식
    private static final Pattern DOCUMENT_HEADER = Pattern.compile(
            "^\\s*(고\\s*시|공\\s*고)\\s*문\\s*(?:[(（]\\s*안\\s*[)）])?\\s*$");

    // 고시·공고 낱말 없이 "평택지방해양수산청  제2018 - 2호"만 있는 줄
    private static final Pattern BARE_NOTICE_NO = Pattern.compile(
            "^\\s*(\\S.*?)\\s*(제\\s*[\\d一-龥]+(?:\\s*[-–—]\\s*[\\d一-龥]*)?\\s*호)\\s*$");

    // 고시자: 직함으로 끝나는 단독 문단
    private static final Pattern SIGNER_PATTERN = Pattern.compile(
            "^[가-힣·\\s]{2,30}(청장|시장|군수|구청장|도지사|지사|본부장|장관|사업소장|소장)$");

    /** 셀 안에서 제목으로 채택할 최대 길이 — 이보다 길면 제목이 아니라 문장으로 본다. */
    private static final int MAX_CELL_TITLE_CHARS = 60;

    /** 인스턴스화 방지 — 정적 휴리스틱 함수만 제공하는 유틸리티 클래스. */
    private Heuristics() {
    }

    /** 문단이 표·그림 캡션("&lt;표 1&gt;", "그림 2" 등)으로 시작하는지 판정한다. */
    public static boolean looksLikeCaption(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        for (Pattern p : CAPTION_PATTERNS) {
            if (p.matcher(trimmed).lookingAt()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 표 주변 문단 중 캡션 패턴에 맞는 첫 번째 텍스트를 반환.
     * anchorIndex 앞뒤(-2 ~ +2 범위)에서 탐색한다.
     */
    public static String nearestCaption(List<String> paragraphTexts, int anchorIndex) {
        int size = paragraphTexts.size();
        for (int idx = Math.max(0, anchorIndex - 2); idx < anchorIndex; idx++) {
            if (looksLikeCaption(paragraphTexts.get(idx))) {
                return paragraphTexts.get(idx).trim();
            }
        }
        for (int idx = anchorIndex + 1; idx < Math.min(size, anchorIndex + 3); idx++) {
            if (looksLikeCaption(paragraphTexts.get(idx))) {
                return paragraphTexts.get(idx).trim();
            }
        }
        return null;
    }

    /**
     * 첫 표에서 제목을 추정한다 — 두 서식을 차례로 본다.
     *
     * <ol>
     *   <li>'고시문' 서식(1열, 2행 이상, 1행에 고시번호) → 2행 첫 줄</li>
     *   <li>허가증·증서 서식(한 셀에 번호와 제목이 줄바꿈으로 함께) → 번호 줄 다음 줄</li>
     * </ol>
     *
     * <p>둘 다 아니면 null. 허가증류는 문단이 하나도 없어 표에서 못 뽑으면 제목이 영영 없다.
     */
    public static String guessTitleFromTables(List<RawTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return null;
        }
        RawTable first = tables.get(0);
        String title = titleFromNoticeForm(first);
        return title != null ? title : titleInsideCell(first);
    }

    /** '고시문' 서식(1열, 1행에 고시번호)에서 2행 첫 줄을 제목 후보로 반환. */
    private static String titleFromNoticeForm(RawTable table) {
        if (table.getNCols() != 1 || table.getNRows() < 2) {
            return null;
        }
        List<List<String>> grid = table.getGrid();
        if (grid == null || grid.size() < 2
                || grid.get(0).isEmpty() || grid.get(1).isEmpty()) {
            return null;
        }
        String headerCell = grid.get(0).get(0);
        if (headerCell == null || !NOTICE_NUMBER_PATTERN.matcher(headerCell).find()) {
            return null;
        }
        String secondCell = grid.get(1).get(0);
        if (secondCell == null) {
            return null;
        }
        String firstLine = secondCell.split("\n", 2)[0].trim();
        return firstLine.isEmpty() ? null : firstLine;
    }

    /**
     * 셀 한 칸 안에서 "번호 줄 → 제목 줄"을 읽는다(허가증·변경허가증 서식).
     *
     * <p>병합 셀은 같은 내용이 옆 칸으로 복제되므로 연속 중복을 접고 훑는다.
     */
    private static String titleInsideCell(RawTable table) {
        List<List<String>> grid = table.getGrid();
        if (grid == null) {
            return null;
        }
        for (List<String> row : grid) {
            for (String cell : Tables.dedupeConsecutive(row)) {
                String title = titleAfterNumberLine(cell);
                if (title != null) {
                    return title;
                }
            }
        }
        return null;
    }

    /**
     * 셀의 첫 줄이 번호 표기 단독이면 그 다음 비어있지 않은 줄을 제목으로 돌려준다.
     *
     * <p>다음 줄이 라벨·날짜·서명이거나 {@value #MAX_CELL_TITLE_CHARS}자를 넘으면 포기한다 —
     * 번호 아래에 곧바로 본문 문장이 오는 서식에서 문장을 제목으로 굳히지 않기 위함이다.
     */
    private static String titleAfterNumberLine(String cell) {
        if (cell == null || cell.isEmpty()) {
            return null;
        }
        String[] lines = cell.split("\n");
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i == lines.length || !DOCUMENT_NUMBER_LINE.matcher(lines[i]).matches()) {
            return null;
        }
        for (i++; i < lines.length; i++) {
            String next = lines[i].trim();
            if (next.isEmpty()) {
                continue;
            }
            if (next.length() > MAX_CELL_TITLE_CHARS || Labels.isLabelLine(next)
                    || Dates.toIso(next).isPresent() || looksLikeSigner(next)) {
                return null;
            }
            return next;
        }
        return null;
    }

    /**
     * "군산지방해양수산청 고시 제2026-47호" 형태에서 {기관, 고시번호}를 분리.
     * 기관 없는 "공고 제56호"류는 기관을 null로 돌려준다. 형태가 아니면 empty.
     */
    public static Optional<String[]> agencyAndNoticeNo(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = AGENCY_AND_NO_PATTERN.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        String agency = m.group(1) == null || m.group(1).isBlank() ? null : m.group(1).trim();
        return Optional.of(new String[]{agency, normalizeNoticeNo(m.group(2))});
    }

    /**
     * 앞 줄까지 보고 고시번호를 찾는다 — "고 시 문" 머리글이 따로 있는 서식용.
     *
     * <pre>
     * 고   시   문                        ← previous
     * 평택지방해양수산청  제2018 - 2호     ← text (고시·공고 낱말이 없다)
     * </pre>
     *
     * <p>한 줄만 보는 {@link #agencyAndNoticeNo(String)}으로는 이 서식이 잡히지 않아
     * 기관·번호가 비고 제목 폴백도 죽는다. 앞 줄이 머리글일 때만 허용해 "제N호"로 끝나는
     * 아무 줄이나 고시번호로 오인하지 않는다.
     *
     * @param previous 바로 앞 줄(없으면 null)
     */
    public static Optional<String[]> agencyAndNoticeNo(String previous, String text) {
        Optional<String[]> hit = agencyAndNoticeNo(text);
        if (hit.isPresent() || previous == null || text == null) {
            return hit;
        }
        Matcher header = DOCUMENT_HEADER.matcher(previous.trim());
        if (!header.matches() || Labels.isLabelLine(text)) {
            return Optional.empty();
        }
        Matcher m = BARE_NOTICE_NO.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        String kind = header.group(1).replaceAll("\\s+", "");
        String agency = m.group(1).isBlank() ? null : m.group(1).trim();
        return Optional.of(new String[]{agency, normalizeNoticeNo(kind + m.group(2))});
    }

    /** 고시번호 표기 정규화: 내부 공백 제거 후 "고시/공고 제…호" 한 칸 띄어쓰기로 통일. */
    private static String normalizeNoticeNo(String notiSn) {
        String compact = notiSn.replaceAll("\\s+", "");
        return compact.replaceFirst("^(고시|공고)제", "$1 제");
    }

    /**
     * 자간 벌림 서식("방 치 선 박  제 거 공 고")을 일반 표기("방치선박 제거공고")로 되돌린다.
     * 공백으로 나뉜 토큰이 전부 한 글자일 때만 동작한다 — 일반 문장은 그대로 반환.
     * 단어 경계는 2칸 이상의 공백으로 판단한다.
     */
    public static String collapseSpacedText(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || !trimmed.contains(" ")) {
            return trimmed;
        }
        for (String token : trimmed.split("\\s+")) {
            if (token.codePointCount(0, token.length()) > 1) {
                return trimmed;
            }
        }
        StringBuilder out = new StringBuilder();
        for (String word : trimmed.split("\\s{2,}")) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(word.replaceAll("\\s+", ""));
        }
        return out.toString();
    }

    /** 고시자로 보이는 단독 문단인지 (직함 종결: 청장/시장/군수/…). */
    public static boolean looksLikeSigner(String text) {
        if (text == null) {
            return false;
        }
        return SIGNER_PATTERN.matcher(cleanSigner(text)).matches();
    }

    /** 고시자 문단 정리: 직함 뒤에 붙는 도장 자리 특수문자 등 비한글 꼬리를 제거한다. */
    public static String cleanSigner(String text) {
        return text == null ? "" : text.trim().replaceAll("[^가-힣·\\s]+\\s*$", "").trim();
    }
}
