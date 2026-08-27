package com.onnara.extract.common;

import java.util.Locale;
import java.util.Optional;

/**
 * 파이프라인 단계 — 첨부 1건이 어디서 넘어졌는지.
 *
 * <p>단계 이름이 <b>콘솔 출력과 DB 값을 겸하고 있었다.</b> 콘솔에는 "(추출) 읽지 못했습니다"처럼
 * 한글이 나가야 읽히고, DB에는 표준코드가 들어가야 집계가 된다. 한 문자열로 둘을 겸하면
 * 둘 중 하나는 반드시 어색해지므로 여기서 가른다 — {@link #label()}은 사람이 읽고
 * {@link #code()}는 {@code OS_ATCH_FILE_DTL.FAIL_STEP_CD}로 간다.
 *
 * <p>허용값은 표준코드 {@code CD_FAIL_STEP}이며 {@code db/standard_terms.json}에 등재돼 있다.
 */
public enum LoadStep {

    /** 형식·암호·스캔 여부 판별. 여기서 넘어지면 본문을 아예 열지 못한 것이다. */
    DETECT("DTCT", "판별"),

    /** 본문·표·이미지 추출. */
    EXTRACT("EXTC", "추출"),

    /** 병합 셀을 편 표를 논리 행으로 해석. */
    TABLE("TBIT", "표해석"),

    /** 라벨을 표준항목으로 매핑. */
    MAP("MAPP", "매핑"),

    /** 중간 산출물(raw·schema JSON) 저장. */
    SAVE("SAVE", "저장");

    /** 표준코드 값 — DB로 가는 것은 이쪽이다. */
    private final String code;

    /** 콘솔·리포트에 찍히는 한글 이름. */
    private final String label;

    LoadStep(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 표준코드 값({@code CD_FAIL_STEP}). */
    public String code() {
        return code;
    }

    /** 사람이 읽는 단계 이름. */
    public String label() {
        return label;
    }

    /**
     * 표준코드 값으로 단계를 찾는다.
     *
     * <p>모르는 값이면 비어 있는 결과를 준다 — 옛 산출물(한글 단계명이 실린 {@code --failures}
     * JSON)을 다시 먹여도 죽지 않아야 한다.
     */
    public static Optional<LoadStep> ofCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String wanted = code.trim().toUpperCase(Locale.ROOT);
        for (LoadStep step : values()) {
            if (step.code.equals(wanted)) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }
}
