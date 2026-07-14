package com.onnara.extract.common;

import com.onnara.extract.common.model.RawTable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablesTest {

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

    @Test
    void cleanGridOnAllEmptyReturnsEmpty() {
        List<List<String>> cleaned = Tables.cleanGrid(List.of(Arrays.asList("", null)));
        assertTrue(cleaned.isEmpty());
    }

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
