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

    // 관공서 "고시문" 서식 패턴: "OO청 고시 제2026-88호" 형태
    private static final Pattern NOTICE_NUMBER_PATTERN =
            Pattern.compile("(고시|공고)\\s*제\\s*[\\d一-龥]+[-–][\\d一-龥]+\\s*호");

    // "군산지방해양수산청 고시 제2026-47호" → 기관 + 번호 분리
    private static final Pattern AGENCY_AND_NO_PATTERN = Pattern.compile(
            "^\\s*(\\S.*?)\\s*((?:고시|공고)\\s*제\\s*[\\d一-龥]+[-–][\\d一-龥]+\\s*호)\\s*$");

    // 고시자: 직함으로 끝나는 단독 문단
    private static final Pattern SIGNER_PATTERN = Pattern.compile(
            "^[가-힣·\\s]{2,30}(청장|시장|군수|구청장|도지사|지사|본부장|장관|사업소장|소장)$");

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
     * tables.get(0)이 '고시문' 서식(1열, 2행 이상, 1행에 고시번호)이면
     * 2행 첫 줄을 실제 제목 후보로 반환. 패턴에 안 맞으면 null.
     */
    public static String guessTitleFromTables(List<RawTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return null;
        }
        RawTable first = tables.get(0);
        if (first.getNCols() != 1 || first.getNRows() < 2) {
            return null;
        }
        List<List<String>> grid = first.getGrid();
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
     * "군산지방해양수산청 고시 제2026-47호" 형태에서 {기관, 고시번호}를 분리.
     * 형태가 아니면 empty.
     */
    public static Optional<String[]> agencyAndNoticeNo(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = AGENCY_AND_NO_PATTERN.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new String[]{m.group(1).trim(), m.group(2).replaceAll("\\s+", " ").trim()});
    }

    /** 고시자로 보이는 단독 문단인지 (직함 종결: 청장/시장/군수/…). */
    public static boolean looksLikeSigner(String text) {
        if (text == null) {
            return false;
        }
        return SIGNER_PATTERN.matcher(text.trim()).matches();
    }
}
