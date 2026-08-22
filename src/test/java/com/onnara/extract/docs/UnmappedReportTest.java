package com.onnara.extract.docs;

import com.onnara.extract.common.model.NoticeRecord;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.SchemaResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link UnmappedReport} 미적재 첨부 판정 단위 테스트. */
class UnmappedReportTest {

    /** 레코드 하나를 담은 스키마 픽스처. */
    private static SchemaResult schema(NoticeRecord record) {
        SchemaResult s = new SchemaResult("148_고시양식 (3).hml", "hml", false, "hml-dom");
        s.setNotiSn(148);
        s.setAtchSn(1);
        s.setBodyCharCnt(389);
        s.getRecords().add(record);
        return s;
    }

    /** 문단 몇 줄짜리 원시 문서. */
    private static RawDocument raw(String... paragraphs) {
        RawDocument d = new RawDocument("148_고시양식 (3).hml", "hml", false);
        for (String p : paragraphs) {
            RawParagraph para = new RawParagraph();
            para.setText(p);
            d.getContent().add(para);
        }
        return d;
    }

    /**
     * 라벨:값을 다 갖고도 표준항목으로 하나도 못 간 첨부가 대상이다.
     *
     * <p>실제 사례 그대로다 — 머리기호 {@code ㅇ}가 라벨 정규화에서 안 벗겨져 처분사유·근거법령이
     * 전부 extras에 머물렀고, DB에는 첨부 행만 남고 값은 0건이 됐다.
     */
    @Test
    void reportsAttachmentWhoseContentReachedNothing() {
        NoticeRecord record = new NoticeRecord.Builder()
                .set("NOTI_DT", "2024-01-23")
                .set("NOTI_PSN", "인천지방해양수산청장")
                .extra("ㅇ처분사유", "「공유수면 관리 및 매립에 관한 법률」제17조제1항 위반")
                .extra("ㅇ근거법령", "「공유수면 관리 및 매립에 관한 법률」제19조제1항제3호")
                .build();

        UnmappedReport.Unmapped row =
                UnmappedReport.of(schema(record), raw("인천지방해양수산청 고시 제2024-8호")).orElseThrow();

        assertEquals("148_고시양식 (3).hml", row.atchFileNm());
        assertEquals(148, row.notiSn());
        assertEquals(1, row.atchSn());
        assertEquals(389, row.bodyCharCnt());
        assertEquals(2, row.extras().size());
        assertTrue(row.extras().containsKey("ㅇ처분사유"));
        assertTrue(row.bodyExcerpt().contains("제2024-8호"));
    }

    /**
     * 문서 메타만 있고 항목값이 0건이면 여전히 대상이다.
     *
     * <p>고시번호·고시일자·제목은 첨부 <b>컬럼</b>으로 가지 항목값 행이 아니다. 이것만 채워진
     * 첨부는 "적재됐다"처럼 보이지만 실제로 처분 내용은 하나도 안 들어갔다.
     */
    @Test
    void countsDocumentMetaAsNothingLoaded() {
        NoticeRecord record = new NoticeRecord.Builder()
                .set("NOTI_NO", "고시 제2021-178호")
                .set("NOTI_DT", "2021-10-21")
                .set("NOTI_TTL", "공유수면 점용․사용허가의 면제대상 시설 고시")
                .build();

        assertTrue(UnmappedReport.of(schema(record), null).isPresent());
    }

    /** 표준항목이 한 건이라도 들어갔으면 대상이 아니다 — 리포트는 전멸한 첨부만 다룬다. */
    @Test
    void ignoresAttachmentThatLoadedAtLeastOneValue() {
        NoticeRecord record = new NoticeRecord.Builder()
                .set("NOTI_NO", "고시 제2026-47호")
                .set("AREA", "367,120.2㎡")
                .build();

        assertTrue(UnmappedReport.of(schema(record), null).isEmpty());
    }

    /**
     * 기간은 시작/종료가 daterange 한 행으로 합쳐져 적재된다 — 그것도 "들어간" 것이다.
     *
     * <p>적재 판정을 리포트가 따로 구현했다면 여기서 갈렸을 자리다.
     */
    @Test
    void countsTheMergedPeriodRowAsLoaded() {
        NoticeRecord record = new NoticeRecord.Builder()
                .set("WORK_PERIOD_START", "2025-09-01")
                .set("WORK_PERIOD_END", "2028-08-31")
                .build();

        assertTrue(UnmappedReport.of(schema(record), null).isEmpty(),
                "기간 한 행이 적재되므로 미적재가 아니다");
    }

    /**
     * 뒤집힌 기간은 행을 만들지 않는다 — 그것뿐이면 미적재 첨부다.
     *
     * <p>{@code [2008-05-30,2008-05-29]} 같은 값이 실입력에 있다. 그대로 넣으면 daterange
     * 인덱스가 거절하고 배치 전체가 롤백돼 그 첨부가 DB에서 통째로 사라진다.
     */
    @Test
    void treatsAnInvertedPeriodAsNoRow() {
        NoticeRecord record = new NoticeRecord.Builder()
                .set("WORK_PERIOD_START", "2008-05-30")
                .set("WORK_PERIOD_END", "2008-05-29")
                .build();

        assertTrue(UnmappedReport.of(schema(record), null).isPresent(),
                "뒤집힌 기간은 적재되지 않으므로 미적재로 잡혀야 한다");
    }

    /** 적재제외 판정을 받은 첨부는 이미 EXCL_RSN_CTNT에 사유가 남으므로 대상이 아니다. */
    @Test
    void ignoresAttachmentExcludedOnPurpose() {
        SchemaResult s = schema(new NoticeRecord.Builder().build());
        s.setExclRsnCtnt("본문 8,355자 (임계 5,000자 초과)");

        assertTrue(UnmappedReport.of(s, null).isEmpty());
    }

    /** extras도 본문도 없으면 빈 값으로 남긴다 — 그래도 파일명은 남아야 추적이 된다. */
    @Test
    void reportsEvenWhenThereIsNothingToShow() {
        UnmappedReport.Unmapped row =
                UnmappedReport.of(schema(new NoticeRecord.Builder().build()), null).orElseThrow();

        assertTrue(row.extras().isEmpty());
        assertNull(row.bodyExcerpt());
        assertEquals("148_고시양식 (3).hml", row.atchFileNm());
    }
}
