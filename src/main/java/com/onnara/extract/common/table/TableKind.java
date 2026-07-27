package com.onnara.extract.common.table;

import com.fasterxml.jackson.annotation.JsonValue;

/** 표 해석 결과의 서식 유형 — 어떤 규칙으로 읽었는지를 산출물에 남긴다. */
public enum TableKind {

    /** 상단 헤더 행 + 데이터 행 N개의 목록표 — 행마다 독립 레코드가 된다. */
    HEADER_LIST("header_list"),

    /** 라벨 칸과 값 칸이 짝을 이루는 서식표 — 문서 1건의 필드를 채운다. */
    LABEL_VALUE("label_value"),

    /** 라벨을 하나도 읽어내지 못한 표(장식용 표·빈 표 등). */
    UNKNOWN("unknown");

    /** JSON에 기록되는 식별자. */
    private final String json;

    /** JSON 식별자를 지정해 생성한다. */
    TableKind(String json) {
        this.json = json;
    }

    /** JSON 직렬화 값 — snake_case 식별자. */
    @JsonValue
    public String json() {
        return json;
    }
}
