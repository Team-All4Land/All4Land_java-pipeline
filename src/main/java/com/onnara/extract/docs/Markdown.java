package com.onnara.extract.docs;

/** 검토 문서 생성기가 공유하는 Markdown 이스케이프·요약 헬퍼. */
final class Markdown {

    /** 표 칸에 넣을 값의 최대 길이 — 넘으면 말줄임한다. */
    private static final int CELL_LIMIT = 60;

    /** 인스턴스화 방지 — 정적 헬퍼만 제공하는 유틸리티 클래스. */
    private Markdown() {
    }

    /** 표 칸 안전 문자열: 줄바꿈을 공백으로, 파이프를 이스케이프하고 길이를 제한한다. */
    static String cell(String text) {
        if (text == null || text.isBlank()) {
            return "—";
        }
        String flat = text.replaceAll("\\s+", " ").trim().replace("|", "\\|");
        return flat.length() <= CELL_LIMIT ? flat : flat.substring(0, CELL_LIMIT - 1) + "…";
    }

    /** 표 칸에 인라인 코드로 넣는다(빈 값은 —). */
    static String code(String text) {
        return text == null || text.isBlank() ? "—" : "`" + cell(text) + "`";
    }
}
