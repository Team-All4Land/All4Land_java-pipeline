package com.onnara.extract;

import com.onnara.extract.model.ExtractedTable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 캡션 패턴 매칭과 "고시문" 제목 추정을 담당하는 공통 유틸.
 * Python 버전(hwpx_extractor.py)의 looks_like_caption / guess_title_from_tables와
 * 동일한 로직을 그대로 옮긴 것.
 */
public final class TextPatterns {

    private static final Pattern[] CAPTION_PATTERNS = new Pattern[]{
            Pattern.compile("^\\s*[<\\[]?\\s*(표|Table)\\s*[\\d一-龥]*\\s*[>\\]]?"),
            Pattern.compile("^\\s*[<\\[]?\\s*(그림|Figure|Fig\\.?|사진)\\s*[\\d一-龥]*\\s*[>\\]]?"),
    };

    // 관공서 "고시문" 서식 패턴: "OO청 고시 제2026-88호" 형태
    private static final Pattern NOTICE_NUMBER_PATTERN =
            Pattern.compile("고시\\s*제\\s*[\\d一-龥]+[-–][\\d一-龥]+\\s*호");

    private TextPatterns() {
    }

    public static boolean looksLikeCaption(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return false;
        for (Pattern p : CAPTION_PATTERNS) {
            if (p.matcher(trimmed).find() && p.matcher(trimmed).lookingAt()) {
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
    public static String guessTitleFromTables(List<ExtractedTable> tables) {
        if (tables == null || tables.isEmpty()) return null;
        ExtractedTable first = tables.get(0);
        if (first.getColCount() != 1 || first.getRowCount() < 2) return null;
        List<List<String>> grid = first.getGrid();
        if (grid.isEmpty() || grid.get(0).isEmpty() || grid.get(1).isEmpty()) return null;

        String headerCell = grid.get(0).get(0);
        if (headerCell == null || !NOTICE_NUMBER_PATTERN.matcher(headerCell).find()) return null;

        String secondCell = grid.get(1).get(0);
        if (secondCell == null) return null;
        String firstLine = secondCell.split("\n", 2)[0].trim();
        return firstLine.isEmpty() ? null : firstLine;
    }
}
