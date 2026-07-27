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
                List.of("공작물의 종류", "선가대(6기)")));

        InterpretedTable result = TableInterpreter.interpret(List.of(table)).get(0);

        assertEquals(TableKind.LABEL_VALUE, result.kind());
        assertEquals(0, result.headerRows());
        assertEquals(1, result.records().size(), "서식표는 문서 범위 레코드 1건에 기여");
        assertTrue(result.records().get(0).documentScope());

        TableFact location = factFor(result, "점용·사용장소");
        assertNotNull(location);
        assertEquals("location", location.canonical());
        assertTrue(location.mapped());
        assertEquals("인천광역시 동구 만석동 인근 공유수면", location.value());
        assertEquals(TableFact.ORIGIN_LABEL_PAIR, location.origin());
        assertEquals(0, location.row());
        assertEquals(1, location.col(), "값이 있던 원본 열이 기록돼야 함");

        // 사전에 없는 라벨도 버리지 않고 미매핑으로 남긴다 (사전 보강 근거)
        TableFact unmapped = factFor(result, "공작물의종류");
        assertNotNull(unmapped);
        assertNull(unmapped.canonical());
        assertFalse(unmapped.mapped());
        assertEquals("선가대(6기)", unmapped.value());
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
        assertEquals("applicant_address", result.columns().get(1).canonical());
        assertEquals("applicant_name", result.columns().get(2).canonical());
        assertEquals("work_description", result.columns().get(3).canonical());

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
        assertEquals("agency", agency.canonical());
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

    /** 라벨을 하나도 못 읽은 표는 unknown으로 남고 레코드를 만들지 않는다. */
    @Test
    void emptyTableIsUnknown() {
        RawTable table = Tables.gridToTable(List.of(List.of("", "")));
        InterpretedTable result = TableInterpreter.interpret(List.of(table)).get(0);

        assertEquals(TableKind.UNKNOWN, result.kind());
        assertTrue(result.records().isEmpty());
    }

    /** 문서 요약이 미매핑 라벨을 모아 사전 보강 후보로 노출하는지 검증한다. */
    @Test
    void summaryCollectsUnmappedLabelsForDictionaryReview() {
        RawTable table = Tables.gridToTable(List.of(
                List.of("점용·사용 면적", "6,060.34㎡"),
                List.of("공작물의 종류", "선가대(6기)"),
                List.of("수면의 종류", "공유수면(인천북항 내)")));

        TableDoc doc = TableDoc.of("고시양식.hml", "hml", false, "hml-dom",
                TableInterpreter.interpret(List.of(table)));

        assertEquals(1, doc.summary().tableCount());
        assertEquals(3, doc.summary().factCount());
        assertEquals(1, doc.summary().mappedCount());
        assertEquals(2, doc.summary().unmappedCount());
        assertEquals(List.of("공작물의종류", "수면의종류"), doc.summary().unmappedLabels());
    }
}
