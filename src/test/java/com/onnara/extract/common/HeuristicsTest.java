package com.onnara.extract.common;

import com.onnara.extract.common.model.RawTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeuristicsTest {

    @Test
    void detectsCaptions() {
        assertTrue(Heuristics.looksLikeCaption("<표 1> 허가 내역"));
        assertTrue(Heuristics.looksLikeCaption("[그림 2] 위치도"));
        assertFalse(Heuristics.looksLikeCaption("일반 문장입니다"));
        assertFalse(Heuristics.looksLikeCaption(null));
    }

    @Test
    void guessesTitleFromNoticeFormTable() {
        RawTable table = Tables.gridToTable(List.of(
                List.of("군산지방해양수산청 고시 제2026-47호"),
                List.of("공유수면 점용·사용 변경허가 고시\n본문...")));
        assertEquals("공유수면 점용·사용 변경허가 고시",
                Heuristics.guessTitleFromTables(List.of(table)));
    }

    @Test
    void titleGuessRejectsNonNoticeTable() {
        RawTable table = Tables.gridToTable(List.of(
                List.of("라벨", "값"),
                List.of("면적", "100㎡")));
        assertNull(Heuristics.guessTitleFromTables(List.of(table)));
        assertNull(Heuristics.guessTitleFromTables(List.of()));
        assertNull(Heuristics.guessTitleFromTables(null));
    }

    @Test
    void splitsAgencyAndNoticeNo() {
        Optional<String[]> hit = Heuristics.agencyAndNoticeNo("군산지방해양수산청 고시 제2026-47호");
        assertTrue(hit.isPresent());
        assertEquals("군산지방해양수산청", hit.get()[0]);
        assertEquals("고시 제2026-47호", hit.get()[1]);
        assertTrue(Heuristics.agencyAndNoticeNo("일반 문장").isEmpty());
    }

    @Test
    void detectsSigner() {
        assertTrue(Heuristics.looksLikeSigner("군산지방해양수산청장"));
        assertTrue(Heuristics.looksLikeSigner("인천광역시장"));
        assertFalse(Heuristics.looksLikeSigner("공유수면 점용·사용 변경허가 고시"));
    }
}
