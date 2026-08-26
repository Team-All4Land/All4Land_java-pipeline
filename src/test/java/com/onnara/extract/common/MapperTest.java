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

/** {@link Mapper} raw→표준 스키마 매핑과 snake_case JSON 호환의 단위 테스트. */
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

    /** 문단 라벨:값이 15개 표준 필드로 매핑되고 미매핑 라벨·이미지가 보존되는지 검증한다. */
    @Test
    void mapsSample8Fields() {
        SchemaResult result = Mapper.mapToSchema(sample8Like(), "hml-dom");

        assertEquals("고시문.hml", result.getAtchFileNm());
        assertEquals("hml", result.getFileExtnNm());
        assertEquals("hml-dom", result.getExtcEngnNm());
        assertEquals(1, result.getRecords().size());

        NoticeRecord record = result.getRecords().get(0);
        assertEquals("군산지방해양수산청", record.bodyAgncyNm());
        assertEquals("고시 제2026-47호", record.notiNo());
        assertEquals("2026-06-17", record.notiYmd());
        assertEquals("공유수면 점용·사용 변경허가 고시", record.notiTtl());
        assertEquals("군산지방해양수산청장", record.notiPsn());
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

    /** ISO로 분리 못 하는 기간 원문이 extras에 보존되는지 검증한다. */
    @Test
    void unparseablePeriodGoesToExtras() {
        RawDocument raw = new RawDocument("x.hml", "hml", false);
        // 가상 필드(work_period)여야 분리를 탄다 — 공사시행기간은 별개 표준항목이라
        // 분리 없이 원문 그대로 남으므로 이 경로를 검증하지 못한다
        raw.getContent().add(new RawParagraph("6. 점용·사용의 기간 : ’25."));
        SchemaResult result = Mapper.mapToSchema(raw);

        NoticeRecord record = result.getRecords().get(0);
        assertNull(record.workPeriodStart());
        assertNull(record.workPeriodEnd());
        assertNotNull(record.extras());
        assertEquals("’25.", record.extras().get(Synonyms.WORK_PERIOD));
    }

    /** 빈 문서도 레코드 1건(모든 필드 null)을 내보내는지 검증한다. */
    @Test
    void emptyDocumentStillEmitsOneRecord() {
        RawDocument raw = new RawDocument("empty.hwp", "hwp", false);
        SchemaResult result = Mapper.mapToSchema(raw);
        assertEquals(1, result.getRecords().size());
        assertNull(result.getRecords().get(0).bodyAgncyNm());
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
        assertNull(record.bodyAgncyNm(), "빈 값 칸 건너뛰어 옆 필드 라벨을 삼키면 안 됨");
    }

    /**
     * 한 파일에 고시 블록이 반복되는 서식을 처분별로 가르는지 검증한다.
     *
     * <p>군산시 풍황계측기 고시 미러 — 허가번호만 바꿔 같은 블록이 세 번 반복된다. 예전에는
     * {@code set()}이 선착순이라 첫 블록만 남고 나머지 처분이 통째로 사라졌다. 문서 메타는
     * 블록마다 상속되고, 블록별 값(장소)은 서로 섞이지 않아야 한다.
     */
    @Test
    void splitsRepeatedNoticeBlocksIntoRecords() {
        RawDocument raw = new RawDocument("고시문.hwp", "hwp", false);
        for (String text : List.of(
                "군산지방해양수산청 고시 제2020-75호",
                "공유수면 점용·사용 승인 고시",
                "2020년 12월 14일",
                "군산지방해양수산청장",
                "1. 승인연월일 : 2020. 12. 14.(허가번호 제2020-14호)",
                "2. 점용·사용목적 : 부유식 해상 풍황계측기 설치",
                "3. 점용·사용의 장소 : 서해 배타적경제수역 가 지점",
                "1. 승인연월일 : 2020. 12. 14.(허가번호 제2020-15호)",
                "2. 점용·사용목적 : 부유식 해상 풍황계측기 설치",
                "3. 점용·사용의 장소 : 서해 배타적경제수역 나 지점",
                "1. 승인연월일 : 2020. 12. 14.(허가번호 제2020-16호)",
                "2. 점용·사용목적 : 부유식 해상 풍황계측기 설치",
                "3. 점용·사용의 장소 : 서해 배타적경제수역 다 지점")) {
            raw.getContent().add(new RawParagraph(text));
        }

        SchemaResult result = Mapper.mapToSchema(raw);
        assertEquals(3, result.getRecords().size(), "블록 3개가 각각 처분이 되어야 함");

        assertEquals("서해 배타적경제수역 가 지점", result.getRecords().get(0).location());
        assertEquals("서해 배타적경제수역 나 지점", result.getRecords().get(1).location());
        assertEquals("서해 배타적경제수역 다 지점", result.getRecords().get(2).location());

        for (NoticeRecord record : result.getRecords()) {
            assertEquals("고시 제2020-75호", record.notiNo(), "문서 메타는 블록마다 상속된다");
            assertEquals("공유수면 점용·사용 승인 고시", record.notiTtl());
            assertEquals("2020-12-14", record.notiYmd());
            assertEquals("부유식 해상 풍황계측기 설치", record.purpose());
        }
    }

    /**
     * 번호가 1로 돌아가도 그 항목이 아직 비어 있으면 가르지 않는지 검증한다(오탐 가드).
     *
     * <p>붙임·중첩 목록에서 번호는 흔히 1로 되돌아간다. "번호가 1로 돌아감"만 조건으로 걸면
     * 실입력 1,816건 중 68건이 잘못 갈라졌다 — 세 조건을 모두 걸어야 9건만 갈라진다.
     */
    @Test
    void doesNotSplitWhenNumberingRestartsWithoutRepeatingAField() {
        RawDocument raw = new RawDocument("고시문.hwp", "hwp", false);
        for (String text : List.of(
                "군산지방해양수산청 고시 제2010-19호",
                "공유수면 점·사용 실시계획 변경승인",
                "1. 시 행 자 : 연수조선(주) 대표이사 이용식",
                "2. 주    소 : 군산시 소룡동 1019-7번지",
                "3. 공사내역",
                "○ 당초 : 선가대 설치",
                "○ 변경 : 선가대 설치 (옹벽 추가)",
                "1. 참고사항 : 관계도서는 사무소에 비치합니다")) {
            raw.getContent().add(new RawParagraph(text));
        }

        SchemaResult result = Mapper.mapToSchema(raw);
        assertEquals(1, result.getRecords().size(), "당초/변경 대비표는 한 처분이다");
    }

    /**
     * 실제 승인사항 고시(부산 강서구) 미러: "피승인자"가 2열에 걸치고 그 아래
     * "주소/성명" 하위 헤더가 오는 2단 병합 헤더 목록표. 하위 헤더 라벨이 열에
     * 매핑되고, 데이터 행이 레코드가 되며, 헤더 행 자체는 레코드가 되면 안 된다.
     */
    @Test
    void mapsTwoRowMergedHeaderTable() {
        RawDocument raw = new RawDocument("승인고시.hwp", "hwp", false);
        raw.getContent().add(new RawParagraph("부산광역시 강서구 고시 제 2018 – 82 호"));
        raw.getContent().add(new RawParagraph("공유수면 점용․사용 승인사항 고시"));
        raw.getContent().add(Tables.gridToTable(List.of(
                List.of("승인번호\n(연월일)", "피승인자", "피승인자", "목적", "장소", "면적", "기간"),
                List.of("승인번호\n(연월일)", "주소", "성명", "목적", "장소", "면적", "기간"),
                List.of("2018-1\n(2018. 8. 10.)", "부산광역시 동구 충장대로 351",
                        "부산항건설사무소", "관측장비 1개소 설치", "가덕도 인근 해역",
                        "2.89㎡", "2018. 8. 10. ~ 2022. 12. 29."))));
        raw.getContent().add(new RawParagraph("2018년 8월 10일"));
        raw.getContent().add(new RawParagraph("부산광역시 강서구청장"));

        SchemaResult result = Mapper.mapToSchema(raw);
        assertEquals(1, result.getRecords().size(), "데이터 행 1건만 레코드가 되어야 함");

        NoticeRecord record = result.getRecords().get(0);
        assertEquals("부산광역시 강서구", record.bodyAgncyNm());
        assertEquals("고시 제2018–82호", record.notiNo());
        assertEquals("공유수면 점용․사용 승인사항 고시", record.notiTtl());
        assertEquals("2018-1", record.approvalNo());
        assertEquals("2018-08-10", record.approvalDate());
        assertEquals("부산광역시 동구 충장대로 351", record.applicantAddress());
        assertEquals("부산항건설사무소", record.applicantName());
        assertEquals("관측장비 1개소 설치", record.purpose());
        assertEquals("가덕도 인근 해역", record.location());
        assertEquals("2.89㎡", record.area());
        assertEquals("2018-08-10", record.workPeriodStart());
        assertEquals("2022-12-29", record.workPeriodEnd());
    }

    /**
     * 실제 고시양식(태항조선) 미러: 2열 라벨/값 표. U+2024 가운뎃점 라벨,
     * "피허가자 성명/주소" 복합 라벨이 매핑되고, "허가번호(년월일)" 값의 괄호
     * 일자가 approval_date로 분리되며, 미매핑 라벨은 extras에 보존돼야 한다.
     */
    @Test
    void mapsLabelValueTableWithDotVariantsAndSplitsApprovalDate() {
        RawDocument raw = new RawDocument("고시양식.hml", "hml", false);
        raw.getContent().add(Tables.gridToTable(List.of(
                List.of("허가번호(년월일)", "제2026-26호(2026. 6. 11.)"),
                List.of("점용․사용 장소", "인천광역시 동구 만석동 인근 공유수면"),
                List.of("점용․사용 면적", "6,060.34㎡"),
                List.of("점용․사용 기간", "2026.7.1 ~ 2028.6.30(2년)"),
                List.of("피허가자 성명", "태항조선㈜ 대표이사"),
                List.of("피허가자 주소", "인천광역시 동구 보세로 62(만석동)"),
                List.of("공작물의 종류", "선가대(6기)"))));

        NoticeRecord record = Mapper.mapToSchema(raw).getRecords().get(0);
        assertEquals("제2026-26호", record.approvalNo());
        assertEquals("2026-06-11", record.approvalDate());
        assertEquals("인천광역시 동구 만석동 인근 공유수면", record.location());
        assertEquals("6,060.34㎡", record.area());
        assertEquals("2026-07-01", record.workPeriodStart());
        assertEquals("2028-06-30", record.workPeriodEnd());
        assertEquals("태항조선㈜ 대표이사", record.applicantName());
        assertEquals("인천광역시 동구 보세로 62(만석동)", record.applicantAddress());
        // 전수 통계 반영으로 표준항목이 된 라벨 — 예전에는 extras로 흘러갔다
        assertEquals("선가대(6기)", record.get("STRUCTURE_TYPE"));
    }

    /**
     * 실제 방치선박 제거공고 미러: 셀 안 "…(좌표: …" / "(담당자 : …" 같은
     * 문장 조각이 extras 라벨로 오탐되면 안 되고(괄호 불균형 가드), 병합 셀이
     * 반복된 서식 라벨:값은 extras에 보존돼야 한다.
     */
    @Test
    void rejectsGarbageExtrasLabelsButKeepsFormFields() {
        RawDocument raw = new RawDocument("공고.hwpx", "hwpx", false);
        raw.getContent().add(Tables.gridToTable(List.of(
                List.of("발견장소", "전북 군산시 비응항(좌표: 35.881237, 126.625565)",
                        "전북 군산시 비응항(좌표: 35.881237, 126.625565)"),
                List.of("제거 예정일자", "2025년 12월 31일 까지", "2025년 12월 31일 까지"),
                List.of("(담당자 : 김현석 전화번호 : 063-733-1323)",
                        "(담당자 : 김현석 전화번호 : 063-733-1323)",
                        "(담당자 : 김현석 전화번호 : 063-733-1323)"))));

        NoticeRecord record = Mapper.mapToSchema(raw).getRecords().get(0);
        assertEquals("전북 군산시 비응항(좌표: 35.881237, 126.625565)", record.location());
        assertNotNull(record.extras());
        assertEquals("2025년 12월 31일 까지", record.extras().get("제거예정일자"));
        for (String key : record.extras().keySet()) {
            assertTrue(key.indexOf('좌') < 0 && key.indexOf('담') < 0,
                    "문장 조각이 extras 라벨로 오탐됨: " + key);
        }
    }

    /**
     * 스키마 JSON의 구조 키는 snake_case, 사전 코드 키는 대문자여야 한다.
     *
     * <p>규칙이 층마다 다르다 — 구조 키는 DB 컬럼에 대응하는 필드명이라 계층 관례를 따르고,
     * records[] 안의 키는 표준항목 <b>코드 값</b>이라 DB·JSON·Java 어디서나 같은 대문자다.
     * camelCase는 어느 쪽으로도 새면 안 된다.
     */
    @Test
    void schemaJsonUsesSnakeCaseKeys() throws Exception {
        RawDocument raw = sample8Like();
        raw.setAtchFilePath("/srv/input/고시.hml");
        String json = Json.MAPPER.writeValueAsString(Mapper.mapToSchema(raw, "hml-dom"));
        assertTrue(json.contains("\"atch_file_nm\""));
        assertTrue(json.contains("\"atch_file_path\":\"/srv/input/고시.hml\""));
        assertTrue(json.contains("\"file_extn_nm\""));
        assertTrue(json.contains("\"scan_yn\""));
        assertTrue(json.contains("\"noti_sn\""), "게시물 일련번호는 구조 키다");
        assertTrue(json.contains("\"WORK_PERIOD_START\""), "사전 코드는 대문자다");
        assertTrue(json.contains("\"APPLICANT_ADDRESS\""));
        assertTrue(json.contains("\"ocr_text\"") || !json.contains("ocrText"));
        assertTrue(!json.contains("\"atchFileNm\""));
    }


    /** raw 문서가 snake_case JSON으로 직렬화·역직렬화 왕복되는지 검증한다. */
    @Test
    void rawJsonRoundTripsSnakeCase() throws Exception {
        RawDocument raw = sample8Like();
        String json = Json.MAPPER.writeValueAsString(raw);
        assertTrue(json.contains("\"atch_file_nm\""));
        assertTrue(json.contains("\"scan_yn\""));
        RawDocument back = Json.MAPPER.readValue(json, RawDocument.class);
        assertEquals(raw.getAtchFileNm(), back.getAtchFileNm());
        assertEquals(raw.getContent().size(), back.getContent().size());
    }
}
