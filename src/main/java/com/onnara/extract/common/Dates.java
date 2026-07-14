package com.onnara.extract.common;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 한국식 날짜 표기 → ISO(yyyy-MM-dd) 정규화.
 *
 * <p>지원: {@code 2024. 1. 5.} / {@code 2024.01.05} / {@code 2024-01-05} /
 * {@code 2024년 1월 5일} / {@code ’25. 2. 25.}(2자리 연도 → 2000+).
 * 파싱 불가·부분 날짜는 empty — DB 컬럼은 NULL로 남긴다(§6).
 */
public final class Dates {

    // 2024. 1. 5. / 2024.1.5 / 2024-01-05 / 2024/1/5
    private static final Pattern NUMERIC = Pattern.compile(
            "(\\d{4})\\s*[.\\-/]\\s*(\\d{1,2})\\s*[.\\-/]\\s*(\\d{1,2})\\s*\\.?");

    // 2024년 1월 5일
    private static final Pattern KOREAN = Pattern.compile(
            "(\\d{4})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");

    // ’25. 2. 25. / '25.2.25 (2자리 연도)
    private static final Pattern SHORT_YEAR = Pattern.compile(
            "[’'`]\\s*(\\d{2})\\s*[.\\-/]\\s*(\\d{1,2})\\s*[.\\-/]\\s*(\\d{1,2})\\s*\\.?");

    // 기간 구분자: ~ ～ ∼ – — 부터/까지
    private static final Pattern RANGE_SEPARATOR = Pattern.compile(
            "\\s*(?:[~～∼–—]|부터)\\s*");

    private Dates() {
    }

    /** 문자열 내 첫 날짜를 ISO로 변환한다. 실패 시 empty. */
    public static Optional<String> toIso(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher m = KOREAN.matcher(text);
        if (m.find()) {
            return build(m.group(1), m.group(2), m.group(3));
        }
        m = NUMERIC.matcher(text);
        if (m.find()) {
            return build(m.group(1), m.group(2), m.group(3));
        }
        m = SHORT_YEAR.matcher(text);
        if (m.find()) {
            return build("20" + m.group(1), m.group(2), m.group(3));
        }
        return Optional.empty();
    }

    /**
     * 기간 문자열을 시작/종료 두 부분으로 나눈다 (예: "2025. 9. 1. ～ 2028. 8. 31.").
     * 구분자가 없으면 empty. 각 부분의 ISO 변환은 호출부에서 {@link #toIso}로 수행.
     */
    public static Optional<String[]> splitRange(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String cleaned = text.replace("까지", "").trim();
        String[] parts = RANGE_SEPARATOR.split(cleaned, 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new String[]{parts[0].trim(), parts[1].trim()});
    }

    private static Optional<String> build(String y, String mo, String d) {
        int year = Integer.parseInt(y);
        int month = Integer.parseInt(mo);
        int day = Integer.parseInt(d);
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return Optional.empty();
        }
        return Optional.of(String.format("%04d-%02d-%02d", year, month, day));
    }
}
