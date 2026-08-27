package com.onnara.extract.common;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "라벨 : 값" 줄 스캔과 extras 라벨 채택 기준 — 문단 경로와 표 경로의 공통 규칙.
 *
 * <p>{@link Mapper}(문단)와 {@link com.onnara.extract.common.table.TableInterpreter}(표 셀)가
 * 같은 규칙을 써야 산출물이 일관되므로 여기 한 곳에 둔다.
 */
public final class Labels {

    // "1. 허가연월일 : 2026. 6. 5." / "가. 주  소 : ..." / "- 위  치 : ..."
    private static final Pattern LABEL_VALUE = Pattern.compile(
            "^\\s*(?:[-–—·o○]\\s*)?(?:\\d{1,2}\\s*[.)]|[가-힣]\\s*[.)]|[①-⑳㉮-㉻])?\\s*"
                    + "([^:：]{1,40}?)\\s*[:：]\\s*(.*)$");

    // 줄 앞 열거 번호("1." "2)")만 따로 뽑는다. LABEL_VALUE에 캡처 그룹을 더하지 않는 이유는
    // 그룹 번호가 밀리면 scan·isLabelLine 호출부가 아무 소리 없이 어긋나기 때문이다.
    private static final Pattern LEADING_NUMBER = Pattern.compile(
            "^\\s*(?:[-–—·o○]\\s*)?(\\d{1,2})\\s*[.)]");

    /** extras에 보존할 라벨의 최대 정규화 길이 — 문장 오탐 방지. */
    private static final int MAX_EXTRA_LABEL_LEN = 20;

    /**
     * 스캔으로 얻은 라벨:값 1쌍.
     *
     * @param label 원시 라벨(정규화 전) — itemCd 매핑과 extras 저장 시 정규화된다
     * @param value 값
     */
    public record Pair(String label, String value) {
    }

    /**
     * 라벨:값 1쌍 + 그 줄의 열거 번호.
     *
     * <p>번호가 필요한 곳은 한 군데뿐이다 — 한 파일에 고시 블록이 통째로 반복되는 서식에서
     * "번호가 1로 되돌아갔다"가 곧 새 처분의 시작이다({@code Mapper}의 처분 블록 분할).
     *
     * @param number 줄 앞 열거 번호. 번호가 없으면 0
     */
    public record Numbered(String label, String value, int number) {
    }

    /** 인스턴스화 방지 — 정적 함수만 제공하는 유틸리티 클래스. */
    private Labels() {
    }

    /** 줄이 "라벨 : 값" 형태인지 — 제목·날짜 문단을 라벨 줄과 구분할 때 쓴다. */
    public static boolean isLabelLine(String text) {
        return LABEL_VALUE.matcher(text).matches();
    }

    /**
     * 줄 목록을 "라벨 : 값" 패턴으로 스캔한다.
     *
     * <p>값이 비어 있으면 다음 줄을 값으로 소비하고, 라벨과 ":값"이 줄바꿈으로
     * 갈라진 서식이면 앞 줄을 라벨로 삼아 이어 붙인다. 값이 빈 쌍은 버린다.
     */
    public static List<Pair> scan(List<String> lines) {
        List<Pair> pairs = new ArrayList<>();
        for (Numbered numbered : scanNumbered(lines)) {
            pairs.add(new Pair(numbered.label(), numbered.value()));
        }
        return pairs;
    }

    /**
     * {@link #scan}과 같은 규칙으로 훑되 각 쌍이 나온 줄의 열거 번호를 함께 돌려준다.
     *
     * <p>{@code scan}은 이 메서드를 감싼 것이다 — 두 벌로 두면 "값이 빈 줄은 다음 줄을
     * 소비한다" 같은 규칙이 한쪽에서만 고쳐져 산출물이 갈린다.
     */
    public static List<Numbered> scanNumbered(List<String> lines) {
        List<Numbered> pairs = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);

            // 이전 줄이 콜론 없는 라벨이고 이번 줄이 ":값" 연속줄인 경우
            if ((text.startsWith(":") || text.startsWith("：")) && i > 0) {
                String prev = lines.get(i - 1);
                if (!isLabelLine(prev)) {
                    add(pairs, prev, text.substring(1).trim(), numberOf(prev));
                    continue;
                }
            }

            Matcher m = LABEL_VALUE.matcher(text);
            if (!m.matches()) {
                continue;
            }
            String value = m.group(2).trim();
            // 값이 비어 있으면 다음 줄을 값으로 소비
            if (value.isEmpty() && i + 1 < lines.size() && !isLabelLine(lines.get(i + 1))) {
                value = lines.get(i + 1).trim();
            }
            add(pairs, m.group(1), value, numberOf(text));
        }
        return pairs;
    }

    /** 줄 앞 열거 번호("1." "2)"). 번호가 없으면 0. */
    public static int numberOf(String line) {
        if (line == null) {
            return 0;
        }
        Matcher m = LEADING_NUMBER.matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 값이 있는 쌍만 결과에 담는다. */
    private static void add(List<Numbered> pairs, String label, String value, int number) {
        if (value != null && !value.isBlank()) {
            pairs.add(new Numbered(label, value, number));
        }
    }

    /**
     * 미매핑 라벨:값을 extras에 보존해도 되는지 판정한다.
     *
     * <p>문장·좌표 등 라벨이 아닌 텍스트의 오탐을 막기 위해 정규화 라벨이
     * {@value #MAX_EXTRA_LABEL_LEN}자 이하이고, 숫자가 없으며, 괄호 짝이 맞는
     * 경우만 채택한다(예: "…비응항(좌표", "(담당자" 제외).
     *
     * @param normalizedLabel {@link NoticeItems#normalizeLabel}을 거친 라벨
     */
    public static boolean acceptableExtra(String normalizedLabel, String value) {
        if (normalizedLabel == null || normalizedLabel.isEmpty()
                || normalizedLabel.length() > MAX_EXTRA_LABEL_LEN) {
            return false;
        }
        if (normalizedLabel.chars().anyMatch(Character::isDigit)) {
            return false;
        }
        long open = normalizedLabel.chars().filter(ch -> ch == '(').count();
        long close = normalizedLabel.chars().filter(ch -> ch == ')').count();
        if (open != close) {
            return false;
        }
        return value != null && value.chars().anyMatch(Character::isLetterOrDigit);
    }
}
