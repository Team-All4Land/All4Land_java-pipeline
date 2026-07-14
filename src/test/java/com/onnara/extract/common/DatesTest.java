package com.onnara.extract.common;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatesTest {

    @Test
    void parsesDottedDates() {
        assertEquals(Optional.of("2026-06-05"), Dates.toIso("2026. 6. 5."));
        assertEquals(Optional.of("2024-01-05"), Dates.toIso("2024.01.05"));
        assertEquals(Optional.of("2024-01-05"), Dates.toIso("2024-01-05"));
    }

    @Test
    void parsesKoreanDates() {
        assertEquals(Optional.of("2026-06-17"), Dates.toIso("2026년 06월 17일"));
        assertEquals(Optional.of("2024-01-05"), Dates.toIso("2024년 1월 5일"));
    }

    @Test
    void parsesShortYearWithApostrophe() {
        assertEquals(Optional.of("2025-02-25"), Dates.toIso("’25. 2. 25."));
    }

    @Test
    void partialOrInvalidDatesAreEmpty() {
        assertTrue(Dates.toIso("’25.").isEmpty());
        assertTrue(Dates.toIso("미정").isEmpty());
        assertTrue(Dates.toIso("").isEmpty());
        assertTrue(Dates.toIso(null).isEmpty());
        assertTrue(Dates.toIso("2024. 13. 1.").isEmpty());
    }

    @Test
    void splitsRanges() {
        assertArrayEquals(new String[]{"2025. 9. 1.", "2028. 8. 31."},
                Dates.splitRange("2025. 9. 1. ～ 2028. 8. 31.").orElseThrow());
        assertArrayEquals(new String[]{"2025. 9. 1.", "2028. 8. 31."},
                Dates.splitRange("2025. 9. 1.부터 2028. 8. 31.까지").orElseThrow());
        assertTrue(Dates.splitRange("2025. 9. 1.").isEmpty());
        assertTrue(Dates.splitRange(null).isEmpty());
    }

    @Test
    void rangeHalvesConvertToIso() {
        String[] halves = Dates.splitRange("2025. 9. 1. ~ 2028. 8. 31.").orElseThrow();
        assertEquals(Optional.of("2025-09-01"), Dates.toIso(halves[0]));
        assertEquals(Optional.of("2028-08-31"), Dates.toIso(halves[1]));
    }
}
