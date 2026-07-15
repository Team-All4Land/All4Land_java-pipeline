package com.onnara.extract.common;

import com.onnara.extract.common.model.RawTable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Tables} 격자 정리·표 변환 유틸 단위 테스트. */
class TablesTest {

    /** cleanGrid가 완전히 빈 행과 열을 제거하는지 검증한다. */
    @Test
    void cleanGridDropsEmptyRowsAndColumns() {
        List<List<String>> grid = List.of(
                Arrays.asList("라벨", null, "값"),
                Arrays.asList("", "", ""),
                Arrays.asList("면적", null, "100㎡"));
        List<List<String>> cleaned = Tables.cleanGrid(grid);
        assertEquals(2, cleaned.size());
        assertEquals(List.of("라벨", "값"), cleaned.get(0));
        assertEquals(List.of("면적", "100㎡"), cleaned.get(1));
    }

    /** 전부 빈 격자는 빈 결과를 반환하는지 검증한다. */
    @Test
    void cleanGridOnAllEmptyReturnsEmpty() {
        List<List<String>> cleaned = Tables.cleanGrid(List.of(Arrays.asList("", null)));
        assertTrue(cleaned.isEmpty());
    }

    /** gridToTable이 행·열 수를 세고 모든 셀 span을 1로 채우는지 검증한다. */
    @Test
    void gridToTableFillsUnitSpans() {
        RawTable table = Tables.gridToTable(List.of(
                List.of("a", "b"),
                List.of("c", "d")));
        assertEquals(2, table.getNRows());
        assertEquals(2, table.getNCols());
        assertEquals(4, table.getCells().size());
        assertEquals(1, table.getCells().get(0).getRowSpan());
        assertEquals(1, table.getCells().get(0).getColSpan());
        assertEquals("d", table.getGrid().get(1).get(1));
    }
}
