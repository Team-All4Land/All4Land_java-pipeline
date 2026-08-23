package com.onnara.extract.docs;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.onnara.extract.common.AttributeRows;
import com.onnara.extract.common.model.NoticeRecord;
import com.onnara.extract.common.model.RawContent;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;
import com.onnara.extract.common.model.SchemaResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 추출은 됐는데 DB에 <b>한 줄도</b> 들어가지 못한 첨부를 파일 단위로 남긴다.
 *
 * <p>세 갈래를 갈라 봐야 한다. 실패({@code PROC_STTS_CD='failed'})는 왜 못 읽었는지가 {@code FAIL_*}에
 * 남고, 적재제외({@code EXCL_RSN_CTNT})는 왜 뺐는지가 그 컬럼에 남는다. 둘 다 이유가 DB에 있다.
 * 남는 것이 <b>"읽히기는 했는데 값이 하나도 안 들어간"</b> 첨부인데, 이것만은 DB에 흔적이 없다 —
 * 첨부 행은 {@code ok}로 멀쩡히 서 있고 항목값만 0건이라, 질의로는 그냥 값 없는 문서와 구분되지 않는다.
 *
 * <p>실제로 이런 첨부가 무엇을 잃는지: {@code 148_고시양식 (3).hml}은 처분사유·근거법령·피승인자를
 * 라벨:값으로 다 갖고 있는데 머리기호 {@code ㅇ}가 라벨 정규화에서 안 벗겨져 전부 {@code extras}에
 * 머물렀다. 사전 구멍이 데이터 손실로 이어진 자리이고, 그것을 사람이 볼 수 있게 하는 것이 이 리포트다.
 *
 * <p>적재 대상 여부는 {@link AttributeRows}가 판정한다 — 적재하는 쪽과 같은 코드를 써야
 * "리포트에는 없는데 DB에도 없는" 첨부가 생기지 않는다.
 */
public final class UnmappedReport {

    /** 본문 발췌 길이. 사람이 "무슨 문서인지" 알아보는 데 필요한 만큼만 남긴다. */
    private static final int EXCERPT_CHARS = 1_000;

    /**
     * 첨부 1건의 미적재 내역.
     *
     * @param atchFileNm       첨부파일명
     * @param notiSn       게시물 일련번호
     * @param atchSn       첨부 순번
     * @param extcEngnNm       추출 엔진 — 엔진별로 쏠리면 엔진 문제이지 사전 문제가 아니다
     * @param bodyCharCnt  본문 글자 수
     * @param extras       표준항목으로 매핑되지 못한 라벨:값. 사전 보강 후보다
     * @param bodyExcerpt  본문 앞부분 — extras마저 없을 때 무슨 문서인지 알 유일한 단서다
     */
    @JsonPropertyOrder({"atch_file_nm", "noti_sn", "atch_sn", "extc_engn_nm", "body_char_cnt",
            "extras", "body_excerpt"})
    public record Unmapped(
            @JsonProperty("atch_file_nm") String atchFileNm,
            @JsonProperty("noti_sn") int notiSn,
            @JsonProperty("atch_sn") int atchSn,
            @JsonProperty("extc_engn_nm") String extcEngnNm,
            @JsonProperty("body_char_cnt") int bodyCharCnt,
            @JsonProperty("extras") @JsonInclude(JsonInclude.Include.NON_EMPTY)
            Map<String, String> extras,
            @JsonProperty("body_excerpt") @JsonInclude(JsonInclude.Include.NON_NULL)
            String bodyExcerpt) {
    }

    /** 인스턴스화 방지 — 정적 판정 함수만 제공하는 유틸리티 클래스. */
    private UnmappedReport() {
    }

    /**
     * 이 첨부가 미적재인지 보고, 맞으면 남길 내역을 만든다.
     *
     * @param raw 본문 발췌를 뜨기 위한 원시 문서. 없으면 발췌 없이 만든다
     * @return 값이 한 줄이라도 적재되거나 적재제외 판정을 받았으면 {@link Optional#empty()}
     */
    public static Optional<Unmapped> of(SchemaResult schema, RawDocument raw) {
        if (schema == null || schema.isExcluded()) {
            return Optional.empty();
        }
        if (AttributeRows.countOf(schema.getRecords()) > 0) {
            return Optional.empty();
        }
        return Optional.of(new Unmapped(
                schema.getAtchFileNm(), schema.getNotiSn(), schema.getAtchSn(),
                schema.getExtcEngnNm(), schema.getBodyCharCnt(),
                extrasOf(schema.getRecords()), excerptOf(raw)));
    }

    /** 레코드들의 extras를 한 맵으로 합친다. 같은 라벨이 겹치면 처음 값을 지킨다. */
    private static Map<String, String> extrasOf(List<NoticeRecord> records) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (NoticeRecord record : records) {
            if (record.extras() != null) {
                record.extras().forEach(merged::putIfAbsent);
            }
        }
        return merged;
    }

    /**
     * 본문 앞부분을 뜬다 — 문단과 표 캡션만 잇는다.
     *
     * <p>표 격자까지 넣지 않는 이유: 미적재 첨부의 절반은 입찰공고·설계서라 격자가 수천 칸이고,
     * 그걸 다 실으면 리포트가 문서보다 커진다. 무슨 문서인지 가리는 데는 앞머리로 충분하다.
     */
    private static String excerptOf(RawDocument raw) {
        if (raw == null || raw.getContent() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (RawContent item : raw.getContent()) {
            String text = null;
            if (item instanceof RawParagraph p) {
                text = p.getText();
            } else if (item instanceof RawTable t) {
                text = t.getCaption();
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text.trim());
            if (sb.length() >= EXCERPT_CHARS) {
                return sb.substring(0, EXCERPT_CHARS) + "…";
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
