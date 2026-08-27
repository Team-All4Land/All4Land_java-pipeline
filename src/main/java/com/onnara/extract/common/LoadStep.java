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
 * <p><b>자체 실패를 정의하지 않는 구간은 단계를 두지 않는다.</b> 한때 표해석(TBIT)과
 * 매핑(MAPP)이 따로 있었지만 {@code TableInterpreter}·{@code Mapper}에는 {@code throw} 문이
 * 하나도 없어 그 값은 버그로만 찍혔다. 실측 0건인 값이 표준코드 표에 섞여 있으면 표 전체를
 * 믿을 수 없게 되므로 둘을 걷어내고 {@link #EXTRACT}가 그 구간까지 덮게 했다.
 *
 * <p>허용값은 표준코드 {@code CD_FAIL_STEP}이며 {@code db/standard_terms.json}에 등재돼 있다.
 */
public enum LoadStep {

    /** 형식·암호·스캔 여부 판별. 여기서 넘어지면 본문을 아예 열지 못한 것이다. */
    DETECT("DTCT", "판별"),

    /**
     * 본문·표·이미지 추출부터 표준항목 매핑까지.
     *
     * <p>표해석과 매핑이 이 값에 함께 담긴다 — 그 둘은 자체 실패를 정의하지 않으므로
     * 그 구간에서 나온 예외는 <b>파일 문제가 아니라 우리 코드의 버그</b>다. 그런 건이 보이면
     * 파일을 고칠 것이 아니라 코드를 고쳐야 한다.
     */
    EXTRACT("EXTC", "추출·매핑"),

    /** 중간 산출물({@code raw.json}·{@code schema.json}) <b>파일</b> 저장. DB 적재가 아니다. */
    SAVE("SAVE", "산출물저장"),

    /** {@code OS_*} 테이블 INSERT. 중간 산출물 파일 저장인 {@link #SAVE}와 가른다. */
    LOAD("LOAD", "DB적재");

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
     * <p>모르는 값이면 비어 있는 결과를 준다 — 옛 산출물을 다시 먹여도 죽지 않아야 한다.
     * 한글 단계명이 실린 옛 {@code --failures} JSON뿐 아니라, 지금은 없어진 {@code TBIT}·
     * {@code MAPP}가 실린 산출물도 이 경로로 흡수된다.
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
