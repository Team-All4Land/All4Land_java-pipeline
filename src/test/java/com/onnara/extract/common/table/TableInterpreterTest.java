package com.onnara.extract.common.table;

import com.onnara.extract.common.Tables;
import com.onnara.extract.common.model.RawCell;
import com.onnara.extract.common.model.RawTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TableInterpreter} 표 해석 중간 단계의 단위 테스트. */
class TableInterpreterTest {

    /** 해석 결과에서 정규화 라벨로 fact 하나를 찾는다. */
    private static TableFact factFor(InterpretedTable table, String label) {
        return table.records().stream()
                .flatMap(r -> r.fields().stream())
                .filter(f -> f.label().equals(label))
                .findFirst()
                .orElse(null);
    }

    /**
     * 실제 고시양식(태항조선) 미러: 2열 라벨/값 서식표.
     * 서식 유형이 label_value로 판정되고, 각 라벨:값이 원본 좌표·매핑 여부와 함께
     * 문서 범위 레코드 하나에 담겨야 한다.
     */
    @Test
    void interpretsLabelValueTableWithProvenance() {
        RawTable table = Tables.gridToTable(List.of(
                List.of("점용·사용 장소", "인천광역시 동구 만석동 인근 공유수면"),
                List.of("점용·사용 면적", "6,060.34㎡"),
                List.of("관리번호", "제 - 호")));

        InterpretedTable result = TableInterpreter.interpret(List.of(table)).get(0);

        assertEquals(TableKind.LABEL_VALUE, result.kind());
        assertEquals(0, result.headerRows());
        assertEquals(1, result.records().size(), "서식표는 문서 범위 레코드 1건에 기여");
        assertTrue(result.records().get(0).documentScope());

        TableFact location = factFor(result, "점용·사용장소");
        assertNotNull(location);
        assertEquals("LOC", location.itemCd());
        assertTrue(location.mapped());
        assertEquals("인천광역시 동구 만석동 인근 공유수면", location.value());
        assertEquals(TableFact.ORIGIN_LABEL_PAIR, location.origin());
        assertEquals(0, location.row());
        assertEquals(1, location.col(), "값이 있던 원본 열이 기록돼야 함");

        // 사전에 없는 라벨도 버리지 않고 미매핑으로 남긴다 (사전 보강 근거).
        // '관리번호'는 방치선박 서식의 별개 필드라 일부러 등재하지 않은 라벨이다.
        TableFact unmapped = factFor(result, "관리번호");
        assertNotNull(unmapped);
        assertNull(unmapped.itemCd());
        assertFalse(unmapped.mapped());
        assertEquals("제 - 호", unmapped.value());
    }

    /**
     * 실제 승인사항 고시(부산 강서구) 미러: "피승인자"가 2열에 걸치고 그 아래
     * "주소/성명" 하위 헤더가 오는 2단 병합 헤더 목록표. 헤더 행 수와 열별 매핑이
     * 기록되고, 데이터 행만 레코드가 되어야 한다.
     */
    @Test
    void interpretsHeaderListWithMergedHeaderRows() {
        RawTable table = Tables.gridToTable(List.of(
                List.of("승인번호\n(연월일)", "피승인자", "피승인자", "목적", "장소", "면적", "기간"),
                List.of("승인번호\n(연월일)", "주소", "성명", "목적", "장소", "면적", "기간"),
                List.of("2018-1\n(2018. 8. 10.)", "부산광역시 동구 충장대로 351",
                        "부산항건설사무소", "관측장비 1개소 설치", "가덕도 인근 해역",
                        "2.89㎡", "2018. 8. 10. ~ 2022. 12. 29.")));

        InterpretedTable result = TableInterpreter.interpret(List.of(table)).get(0);

        assertEquals(TableKind.HEADER_LIST, result.kind());
        assertEquals(2, result.headerRows(), "2단 병합 헤더를 헤더 2행으로 인식");
        assertEquals(1, result.records().size(), "데이터 행 1건만 레코드");
        assertEquals(2, result.records().get(0).row(), "원본 데이터 행 인덱스 기록");

        // 하위 헤더(주소/성명)가 상위 헤더(피승인자)보다 우선 매핑돼야 한다
        assertEquals("APLC_ADDR", result.columns().get(1).itemCd());
        assertEquals("APLC_NM", result.columns().get(2).itemCd());
        assertEquals("PRPS", result.columns().get(3).itemCd());

        TableFact name = factFor(result, "성명");
        assertNotNull(name);
        assertEquals(TableFact.ORIGIN_HEADER_COLUMN, name.origin());
        assertEquals("부산항건설사무소", name.value());
    }

    /**
     * 병합 정보(span)가 있으면 반복된 칸을 내용 비교가 아니라 실제 셀 경계로 접는다.
     * 가로 3칸 병합된 라벨 다음의 값 칸이 정확히 짝지어지고, 값의 원본 열 위치가
     * 접기 전 좌표(3열)로 남아야 한다.
     */
    @Test
    void usesMergeSpansToFoldRepeatedCells() {
        RawTable table = new RawTable(1, 4,
                List.of(new RawCell(0, 0, 1, 3, "관 리 청"),
                        new RawCell(0, 3, 1, 1, "군산지방해양수산청")),
                List.of(List.of("관 리 청", "관 리 청", "관 리 청", "군산지방해양수산청")));

        InterpretedTable result = TableInterpreter.interpret(List.of(table)).get(0);

        assertTrue(result.spanAware(), "병합 정보가 있으면 span 기반으로 읽어야 함");
        TableFact agency = factFor(result, "관리청");
        assertNotNull(agency);
        assertEquals("BODY_AGNCY_NM", agency.itemCd());
        assertEquals("군산지방해양수산청", agency.value());
        assertEquals(3, agency.col(), "접기 전 원본 열 좌표가 보존돼야 함");
    }

    /** 병합 정보가 없는 격자(PDF·OCR 경로)는 근사로 읽었음을 산출물에 드러낸다. */
    @Test
    void reportsApproximateFoldingWhenSpansAreUnavailable() {
        RawTable table = Tables.gridToTable(List.of(List.of("면적", "367,120.2㎡")));
        assertFalse(TableInterpreter.interpret(List.of(table)).get(0).spanAware());
    }

    /**
     * 헤더처럼 보이는 행만 있고 데이터 행에서 아무 값도 얻지 못하면 목록표로
     * 확정하지 않고 서식표로 다시 읽어야 한다 — 2열 라벨/값 표의 첫 행이
     * 우연히 헤더 조건을 만족하는 경우를 되살리기 위함이다.
     */
    @Test
    void fallsBackToLabelValueWhenHeaderRowsYieldNoData() {
        RawTable table = Tables.gridToTable(List.of(
                List.of("면적", "기간"),
                List.of("", "")));

        InterpretedTable result = TableInterpreter.interpret(List.of(table)).get(0);
        assertFalse(result.kind() == TableKind.HEADER_LIST,
                "데이터가 없으면 목록표로 확정하면 안 됨");
    }

    /**
     * 실제 OCR 산출(여수 승인사항 고시) 미러: 가운뎃점이 마침표로 읽히고("점용.사용")
     * 셀 안에 시각적 줄바꿈이 남은 2단 병합 헤더 목록표.
     *
     * <p>헤더가 사전과 매칭되지 않으면 목록표 판정이 통째로 실패해 데이터 행이
     * 라벨/값 쌍으로 오독된다 — "여수 섬 요트투어 기반구축"이 extras 키가 되고
     * "1,307 / 130"이 그 값이 되는 회귀를 막는다.
     */
    @Test
    void interpretsOcrHeaderListWithDotSeparatorsAndLineBreaks() {
        RawTable table = Tables.gridToTable(List.of(
                List.of("승인번호\n(승인일)", "점용.사용\n장소", "점용.사용\n목적", "점용.사용\n면적(㎡)",
                        "점용.사용 기간", "피승인자", "피승인자"),
                List.of("승인번호\n(승인일)", "점용.사용\n장소", "점용.사용\n목적", "점용.사용\n면적(㎡)",
                        "점용.사용 기간", "성  명", "주  소"),
                List.of("2025-035\n('25. 11. 27.)", "여수시 남면 두모리\n945-19번지 /\n유송리\n산334-1번지 지선",
                        "여수 섬 요트투어\n기반구축", "1,307 /\n130", "'25. 11. 27. ~ '40. 11. 26.",
                        "전라남도 여수시\n시청로 1\n(학동)", "여수시장\n(섬박람회대책과)")));

        InterpretedTable result = TableInterpreter.interpret(List.of(table)).get(0);

        assertEquals(TableKind.HEADER_LIST, result.kind(), "헤더 목록표로 판정돼야 함");
        assertEquals(2, result.headerRows());
        assertEquals(1, result.records().size(), "데이터 행 1건만 레코드");

        // 마침표로 읽힌 가운뎃점이 정규화돼 열별 표준 필드로 매핑돼야 한다
        assertEquals("APV_NO", result.columns().get(0).itemCd());
        assertEquals("LOC", result.columns().get(1).itemCd());
        assertEquals("PRPS", result.columns().get(2).itemCd());
        assertEquals("AREA", result.columns().get(3).itemCd());
        assertEquals("WORK_PRD", result.columns().get(4).itemCd());
        assertEquals("APLC_NM", result.columns().get(5).itemCd());
        assertEquals("APLC_ADDR", result.columns().get(6).itemCd());

        // 값의 시각적 줄바꿈은 한 칸 공백으로 접힌다
        TableFact location = factFor(result, "점용·사용장소");
        assertNotNull(location);
        assertEquals("여수시 남면 두모리 945-19번지 / 유송리 산334-1번지 지선", location.value());
        TableFact area = factFor(result, "점용·사용면적(㎡)");
        assertNotNull(area);
        assertEquals("1,307 / 130", area.value());

        // 데이터 값이 라벨:값 쌍으로 오독돼 extras로 새지 않아야 한다
        assertTrue(result.records().stream()
                        .flatMap(r -> r.fields().stream())
                        .allMatch(TableFact::mapped),
                "모든 값이 헤더 열 라벨로 매핑돼야 함");
    }

    /** 라벨을 하나도 못 읽은 표는 unknown으로 남고 레코드를 만들지 않는다. */
    @Test
    void emptyTableIsUnknown() {
        RawTable table = Tables.gridToTable(List.of(List.of("", "")));
        InterpretedTable result = TableInterpreter.interpret(List.of(table)).get(0);

        assertEquals(TableKind.UNKNOWN, result.kind());
        assertTrue(result.records().isEmpty());
    }

    /**
     * 문서 요약이 미매핑 라벨을 모아 사전 보강 후보로 노출하는지 검증한다.
     *
     * <p>미매핑 예시는 방치선박 제거공고 서식에서 가져왔다 — '공작물의 종류'·'수면의 종류'는
     * 전수 통계 반영 때 표준항목으로 승격돼 더 이상 미매핑이 아니다.
     */
    @Test
    void summaryCollectsUnmappedLabelsForDictionaryReview() {
        RawTable table = Tables.gridToTable(List.of(
                List.of("점용·사용 면적", "6,060.34㎡"),
                List.of("관리번호", "제 - 호"),
                List.of("선적항", "불명")));

        TableDoc doc = TableDoc.of("방치선박 제거공고.hwpx", "hwpx", false, "owpml",
                TableInterpreter.interpret(List.of(table)));

        assertEquals(1, doc.summary().tableCount());
        assertEquals(3, doc.summary().factCount());
        assertEquals(1, doc.summary().mappedCount());
        assertEquals(2, doc.summary().unmappedCount());
        assertEquals(List.of("관리번호", "선적항"), doc.summary().unmappedLabels());
    }

    /**
     * 전수 통계로 승격된 표준항목이 실제로 매핑되는지 확인한다 — 예전에는 extras로
     * 흘러가던 라벨들이라, 되돌아가면 조용히 회귀한다.
     */
    @Test
    void promotedStandardAttributesAreMappedNotUnmapped() {
        RawTable table = Tables.gridToTable(List.of(
                List.of("공작물의 종류", "선가대(6기)"),
                List.of("수면의 종류", "공유수면(인천북항 내)"),
                List.of("총사업비", "198백만원")));

        InterpretedTable result = TableInterpreter.interpret(List.of(table)).get(0);

        assertEquals("STRC_TY", factFor(result, "공작물의종류").itemCd());
        assertEquals("WTR_TY", factFor(result, "수면의종류").itemCd());
        assertEquals("PRJ_COST", factFor(result, "총사업비").itemCd());
    }
}
