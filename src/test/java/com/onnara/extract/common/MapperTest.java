package com.onnara.extract.common;

import com.onnara.extract.common.model.NoticeRecord;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.SchemaResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperTest {

    /** 샘플 #8(군산 변경허가 고시)의 문단 구조를 미러링한 픽스처. */
    private static RawDocument sample8Like() {
        RawDocument raw = new RawDocument("고시문.hml", "hml", false);
        for (String text : List.of(
                "군산지방해양수산청 고시 제2026-47호",
                "공유수면 점용·사용 변경허가 고시",
                "공유수면 관리 및 매립에 관한 법률 제8조 제4항에 따라 공유수면 점용·사용을 아래와 같이 변경허가 하였음을 고시합니다.",
                "2026년 06월 17일",
                "군산지방해양수산청장",
                "1. 허가연월일 : 2026. 6. 5.",
                "2. 점용·사용목적 : 청소년 해양종합레포츠 교육 및 조종면허실기시험장 운영",
                "3. 점용·사용의 장소 : 군산시 비응로 107 인근 공유수면",
                "4. 점용·사용의 면적 : 367,120.2㎡",
                "5 점용·사용의 기간 : 2025. 9. 1. ～ 2028. 8. 31.",
                "6. 점용·사용 허가를 받은 자",
                "가. 주    소 : 군산시 비응로 107",
                "나. 성    명 : 한국해양소년단 전북연맹",
                "특이사항 : 없음")) {
            raw.getContent().add(new RawParagraph(text));
        }
        RawImage image = new RawImage("고시문_img0.png", 1234);
        image.setPath("images/고시문_img0.png");
        raw.getImages().add(image);
        return raw;
    }

    @Test
    void mapsSample8Fields() {
        SchemaResult result = Mapper.mapToSchema(sample8Like(), "hml-dom");

        assertEquals("고시문.hml", result.getSourceFile());
        assertEquals("hml", result.getFileType());
        assertEquals("hml-dom", result.getEngine());
        assertEquals(1, result.getRecords().size());

        NoticeRecord record = result.getRecords().get(0);
        assertEquals("군산지방해양수산청", record.agency());
        assertEquals("고시 제2026-47호", record.noticeNo());
        assertEquals("2026-06-17", record.noticeDate());
        assertEquals("공유수면 점용·사용 변경허가 고시", record.title());
        assertEquals("군산지방해양수산청장", record.signer());
        assertEquals("2026-06-05", record.approvalDate());
        assertEquals("군산시 비응로 107 인근 공유수면", record.location());
        assertEquals("367,120.2㎡", record.area());
        assertEquals("2025-09-01", record.workPeriodStart());
        assertEquals("2028-08-31", record.workPeriodEnd());
        assertEquals("한국해양소년단 전북연맹", record.applicantName());
        assertEquals("군산시 비응로 107", record.applicantAddress());

        // 미매핑 라벨은 extras에 보존
        assertNotNull(record.extras());
        assertEquals("없음", record.extras().get("특이사항"));

        // 이미지 전달
        assertEquals(1, result.getImages().size());
        assertEquals("images/고시문_img0.png", result.getImages().get(0).getPath());
    }

    @Test
    void unparseablePeriodGoesToExtras() {
        RawDocument raw = new RawDocument("x.hml", "hml", false);
        raw.getContent().add(new RawParagraph("6. 공사시행기간 : ’25."));
        SchemaResult result = Mapper.mapToSchema(raw);

        NoticeRecord record = result.getRecords().get(0);
        assertNull(record.workPeriodStart());
        assertNull(record.workPeriodEnd());
        assertNotNull(record.extras());
        assertEquals("’25.", record.extras().get(Synonyms.WORK_PERIOD));
    }

    @Test
    void emptyDocumentStillEmitsOneRecord() {
        RawDocument raw = new RawDocument("empty.hwp", "hwp", false);
        SchemaResult result = Mapper.mapToSchema(raw);
        assertEquals(1, result.getRecords().size());
        assertNull(result.getRecords().get(0).agency());
    }

    /**
     * 실제 방치선박 제거공고(hwpx) 표에서 재현된 회귀 케이스: "관리청" 라벨의 값 칸이
     * 병합 셀 때문에 비어 있고, 같은 행 뒤쪽에 "관리번호"라는 다른 필드의 라벨이 있다.
     * 값 탐색이 빈 칸을 건너뛰고 옆 필드의 라벨을 값으로 삼키면 안 된다.
     */
    @Test
    void doesNotBorrowNeighboringFieldLabelAsValueAcrossBlankMergedCell() {
        RawDocument raw = new RawDocument("공고.hwpx", "hwpx", false);
        raw.getContent().add(Tables.gridToTable(List.of(
                List.of("관     리     청", "관     리     청", "관     리     청", "", "", "관 리 번 호", "관 리 번 호", "제    -   호"))));

        SchemaResult result = Mapper.mapToSchema(raw);
        NoticeRecord record = result.getRecords().get(0);
        assertNull(record.agency(), "빈 값 칸 건너뛰어 옆 필드 라벨을 삼키면 안 됨");
    }

    @Test
    void schemaJsonUsesSnakeCaseKeys() throws Exception {
        String json = Json.MAPPER.writeValueAsString(Mapper.mapToSchema(sample8Like(), "hml-dom"));
        assertTrue(json.contains("\"source_file\""));
        assertTrue(json.contains("\"file_type\""));
        assertTrue(json.contains("\"is_scanned\""));
        assertTrue(json.contains("\"notice_no\""));
        assertTrue(json.contains("\"work_period_start\""));
        assertTrue(json.contains("\"applicant_address\""));
        assertTrue(json.contains("\"ocr_text\"") || !json.contains("ocrText"));
        assertTrue(!json.contains("\"sourceFile\""));
    }

    @Test
    void rawJsonRoundTripsSnakeCase() throws Exception {
        RawDocument raw = sample8Like();
        String json = Json.MAPPER.writeValueAsString(raw);
        assertTrue(json.contains("\"source_file\""));
        assertTrue(json.contains("\"is_scanned\""));
        RawDocument back = Json.MAPPER.readValue(json, RawDocument.class);
        assertEquals(raw.getSourceFile(), back.getSourceFile());
        assertEquals(raw.getContent().size(), back.getContent().size());
    }
}
