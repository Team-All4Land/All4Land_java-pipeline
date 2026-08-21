package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.onnara.extract.common.Synonyms;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 표준 스키마의 레코드 — 사전에 정의된 표준 필드 값 묶음 + 미매핑 라벨(extras).
 *
 * <p>필드를 고정 컬럼이 아니라 <b>맵</b>으로 들고 있다. 표준항목이 40종이고 문서 종류마다
 * 등장하는 집합이 다르며, 레지스트리 자체가 분석 회차마다 바뀌기 때문이다(08.06판 43항목 →
 * 08.07판 40항목). 고정 필드로 두면 사전이 늘 때마다 이 클래스와 DB 스키마를 함께 고쳐야 한다.
 *
 * <p>자주 쓰는 필드는 편의 접근자로 남겼다 — 호출부가 문자열 키를 외우지 않아도 되고,
 * 오타가 컴파일 시점에 잡힌다.
 *
 * <p>날짜 필드는 ISO(yyyy-MM-dd) 문자열로 유지한다. 적재 시 DbLoader가 변환하며,
 * 파싱 실패는 Mapper가 이미 걸러 원문을 extras에 남긴다.
 *
 * @param fields itemCd 필드명 → 값. 사전 선언 순서로 정렬돼 있어 JSON 산출물이 안정적이다
 * @param extras 사전에 매핑되지 않은 라벨:값 (사전 보강용). 없으면 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NoticeRecord(
        @JsonIgnore Map<String, String> fields,
        @JsonProperty("extras") Map<String, String> extras) {

    /** JSON에는 표준 필드를 평탄하게 편다 — {"LOCATION": "...", "AREA": "...", "extras": {...}}. */
    @JsonAnyGetter
    public Map<String, String> jsonFields() {
        return fields;
    }

    /**
     * 평탄한 JSON을 되읽는다 — {@code extras}를 뺀 나머지 키가 전부 표준 필드다.
     *
     * <p>{@code map} → {@code load}로 나눠 도는 경로에서 {@code *.schema.json}을 다시
     * 읽으므로, 직렬화한 모양 그대로 복원돼야 한다.
     */
    @JsonCreator
    static NoticeRecord fromJson(Map<String, Object> json) {
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> extras = null;
        for (Map.Entry<String, Object> entry : json.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if ("extras".equals(entry.getKey())) {
                @SuppressWarnings("unchecked")
                Map<String, String> parsed = (Map<String, String>) value;
                extras = parsed.isEmpty() ? null : new LinkedHashMap<>(parsed);
            } else {
                fields.put(entry.getKey(), String.valueOf(value));
            }
        }
        return new NoticeRecord(fields, extras);
    }

    /** itemCd 필드명으로 값을 읽는다(없으면 null). */
    public String get(String itemCd) {
        return fields.get(itemCd);
    }

    /** 이 레코드가 값을 하나도 갖지 않는지 — 빈 목록표 행을 걸러낼 때 쓴다. */
    @JsonIgnore
    public boolean isEmpty() {
        return fields.isEmpty() && extras == null;
    }

    // ── 편의 접근자 ──────────────────────────────────────────────────
    // 문서 메타(TB_ATCH_FILE 컬럼이 되는 5개)와 자주 쓰는 처분 값들.

    /** 고시·공고를 발령한 행정기관명. */
    public String bodyAgncyNm() {
        return fields.get("BODY_AGNCY_NM");
    }

    /** 고시·공고의 문서번호. */
    public String notiNo() {
        return fields.get("NOTI_NO");
    }

    /** 고시·공고가 공표된 날짜(ISO). */
    public String notiYmd() {
        return fields.get("NOTI_YMD");
    }

    /** 고시문 제목 — 공고종류 분류의 입력값. */
    public String notiTtl() {
        return fields.get("NOTI_TTL");
    }

    /** 고시문 말미의 발령권자 직함. */
    public String notiPsn() {
        return fields.get("NOTI_PSN");
    }

    /** 처분(허가/승인/신고수리) 번호. */
    public String approvalNo() {
        return fields.get("APPROVAL_NO");
    }

    /** 처분일자(ISO). */
    public String approvalDate() {
        return fields.get("APPROVAL_DATE");
    }

    /** 점용·사용 장소. */
    public String location() {
        return fields.get("LOCATION");
    }

    /** 점용·사용 면적(원문 표기 보존). */
    public String area() {
        return fields.get("AREA");
    }

    /** 점용·사용 목적. */
    public String purpose() {
        return fields.get("PURPOSE");
    }

    /** 점용·사용 기간 시작일(ISO). */
    public String workPeriodStart() {
        return fields.get(Synonyms.WORK_PERIOD_START);
    }

    /** 점용·사용 기간 종료일(ISO). */
    public String workPeriodEnd() {
        return fields.get(Synonyms.WORK_PERIOD_END);
    }

    /** 허가·승인 대상자 성명. */
    public String applicantName() {
        return fields.get("APPLICANT_NAME");
    }

    /** 허가·승인 대상자 주소. */
    public String applicantAddress() {
        return fields.get("APPLICANT_ADDRESS");
    }

    /** 비고. */
    public String remarks() {
        return fields.get("REMARKS");
    }

    /** Mapper가 라벨:값 히트를 누적할 때 쓰는 가변 빌더. */
    public static final class Builder {

        private final Map<String, String> fields = new LinkedHashMap<>();
        private final Map<String, String> extras = new LinkedHashMap<>();

        /** itemCd 필드명으로 값 설정. 이미 값이 있으면 최초 값 유지. */
        public Builder set(String field, String value) {
            if (value != null && !value.isBlank()) {
                fields.putIfAbsent(field, value.trim());
            }
            return this;
        }

        /** 강제 덮어쓰기 (정규화 결과 반영용). */
        public Builder overwrite(String field, String value) {
            if (value == null || value.isBlank()) {
                fields.remove(field);
            } else {
                fields.put(field, value.trim());
            }
            return this;
        }

        /** 현재 세팅된 필드 값을 반환한다(없으면 null). */
        public String get(String field) {
            return fields.get(field);
        }

        /** 해당 필드에 값이 세팅됐는지 여부. */
        public boolean has(String field) {
            return fields.containsKey(field);
        }

        /** 매핑 안 된 라벨:값을 extras에 보존. */
        public Builder extra(String label, String value) {
            if (label != null && !label.isBlank() && value != null && !value.isBlank()) {
                extras.putIfAbsent(label.trim(), value.trim());
            }
            return this;
        }

        /** 표준 필드·extras 모두 비었는지 — 빈 목록표 행을 걸러낼 때 쓴다. */
        public boolean isEmpty() {
            return fields.isEmpty() && extras.isEmpty();
        }

        /**
         * 누적된 값으로 불변 {@link NoticeRecord}를 만든다.
         *
         * <p>필드는 <b>사전 선언 순서</b>로 정렬한다. 삽입 순서를 그대로 두면 같은 서식이라도
         * 표를 읽은 순서에 따라 JSON 키 순서가 달라져, 산출물 diff가 의미 없는 잡음으로 찬다.
         */
        public NoticeRecord build() {
            Map<String, String> ordered = new LinkedHashMap<>();
            for (String itemCd : Synonyms.fieldOrder()) {
                String value = fields.get(itemCd);
                if (value != null) {
                    ordered.put(itemCd, value);
                }
            }
            // 사전에 없는 키(work_period_start/end 같은 파생 필드)는 뒤에 원래 순서로 붙인다
            fields.forEach(ordered::putIfAbsent);
            return new NoticeRecord(
                    Collections.unmodifiableMap(ordered),
                    extras.isEmpty() ? null : new LinkedHashMap<>(extras));
        }
    }
}
