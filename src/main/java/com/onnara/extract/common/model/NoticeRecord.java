package com.onnara.extract.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 표준 스키마(§5)의 레코드 — 15개 표준 필드 + extras.
 *
 * <p>날짜 필드는 ISO(yyyy-MM-dd) 문자열로 유지한다(Python 산출물과 JSON 호환).
 * DbLoader가 적재 시 LocalDate로 변환하며, 파싱 실패는 NULL 허용.
 * extras에는 동의어 사전에 매핑되지 않은 라벨:값 쌍을 보존한다(사전 보강용).
 */
@JsonPropertyOrder({
        "agency", "notice_no", "notice_date", "title", "signer",
        "approval_no", "approval_date", "location", "area",
        "work_description", "work_period_start", "work_period_end",
        "applicant_name", "applicant_address", "remarks", "extras"})
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NoticeRecord(
        @JsonProperty("agency") String agency,
        @JsonProperty("notice_no") String noticeNo,
        @JsonProperty("notice_date") String noticeDate,
        @JsonProperty("title") String title,
        @JsonProperty("signer") String signer,
        @JsonProperty("approval_no") String approvalNo,
        @JsonProperty("approval_date") String approvalDate,
        @JsonProperty("location") String location,
        @JsonProperty("area") String area,
        @JsonProperty("work_description") String workDescription,
        @JsonProperty("work_period_start") String workPeriodStart,
        @JsonProperty("work_period_end") String workPeriodEnd,
        @JsonProperty("applicant_name") String applicantName,
        @JsonProperty("applicant_address") String applicantAddress,
        @JsonProperty("remarks") String remarks,
        @JsonProperty("extras") Map<String, String> extras) {

    /** Mapper가 라벨:값 히트를 누적할 때 쓰는 가변 빌더. */
    public static final class Builder {

        private final Map<String, String> fields = new LinkedHashMap<>();
        private final Map<String, String> extras = new LinkedHashMap<>();

        /** canonical 필드명으로 값 설정. 이미 값이 있으면 최초 값 유지. */
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

        /** 누적된 필드·extras로 불변 {@link NoticeRecord}를 생성한다(extras 없으면 null). */
        public NoticeRecord build() {
            return new NoticeRecord(
                    fields.get("agency"),
                    fields.get("notice_no"),
                    fields.get("notice_date"),
                    fields.get("title"),
                    fields.get("signer"),
                    fields.get("approval_no"),
                    fields.get("approval_date"),
                    fields.get("location"),
                    fields.get("area"),
                    fields.get("work_description"),
                    fields.get("work_period_start"),
                    fields.get("work_period_end"),
                    fields.get("applicant_name"),
                    fields.get("applicant_address"),
                    fields.get("remarks"),
                    extras.isEmpty() ? null : new LinkedHashMap<>(extras));
        }
    }
}
