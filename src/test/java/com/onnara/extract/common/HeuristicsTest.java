package com.onnara.extract.common;

import com.onnara.extract.common.model.RawTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Heuristics} 캡션·제목·기관/번호·고시자 판별 단위 테스트. */
class HeuristicsTest {

    /** 표·그림 캡션 패턴을 맞게 True/False로 구분하는지 검증한다. */
    @Test
    void detectsCaptions() {
        assertTrue(Heuristics.looksLikeCaption("<표 1> 허가 내역"));
        assertTrue(Heuristics.looksLikeCaption("[그림 2] 위치도"));
        assertFalse(Heuristics.looksLikeCaption("일반 문장입니다"));
        assertFalse(Heuristics.looksLikeCaption(null));
    }

    /** 고시문 서식 표(1열, 1행에 고시번호)에서 2행 제목을 뽑는지 검증한다. */
    @Test
    void guessesTitleFromNoticeFormTable() {
        RawTable table = Tables.gridToTable(List.of(
                List.of("군산지방해양수산청 고시 제2026-47호"),
                List.of("공유수면 점용·사용 변경허가 고시\n본문...")));
        assertEquals("공유수면 점용·사용 변경허가 고시",
                Heuristics.guessTitleFromTables(List.of(table)));
    }

    /** 고시문 서식이 아닌 표·빈 목록·null에서는 제목 추정이 null인지 검증한다. */
    @Test
    void titleGuessRejectsNonNoticeTable() {
        RawTable table = Tables.gridToTable(List.of(
                List.of("라벨", "값"),
                List.of("면적", "100㎡")));
        assertNull(Heuristics.guessTitleFromTables(List.of(table)));
        assertNull(Heuristics.guessTitleFromTables(List.of()));
        assertNull(Heuristics.guessTitleFromTables(null));
    }

    /** "OO청 고시 제N호"에서 기관과 고시번호가 분리되는지 검증한다. */
    @Test
    void splitsAgencyAndNoticeNo() {
        Optional<String[]> hit = Heuristics.agencyAndNoticeNo("군산지방해양수산청 고시 제2026-47호");
        assertTrue(hit.isPresent());
        assertEquals("군산지방해양수산청", hit.get()[0]);
        assertEquals("고시 제2026-47호", hit.get()[1]);
        assertTrue(Heuristics.agencyAndNoticeNo("일반 문장").isEmpty());
    }

    /** 대시 양옆 공백("제 2018 – 82 호")도 매칭되고 번호 표기가 정규화되는지 검증한다(승인사항 고시 회귀). */
    @Test
    void splitsNoticeNoWithSpacedDash() {
        Optional<String[]> hit = Heuristics.agencyAndNoticeNo("부산광역시 강서구 고시 제 2018 – 82 호");
        assertTrue(hit.isPresent());
        assertEquals("부산광역시 강서구", hit.get()[0]);
        assertEquals("고시 제2018–82호", hit.get()[1]);
    }

    /** 기관 없는 자간 벌림 "공 고 제 56 호"(연도 대시 없음)도 번호로 인식되는지 검증한다(방치선박 공고 회귀). */
    @Test
    void splitsNoticeNoWithoutAgencyAndDash() {
        Optional<String[]> hit = Heuristics.agencyAndNoticeNo("공 고   제  56 호");
        assertTrue(hit.isPresent());
        assertNull(hit.get()[0]);
        assertEquals("공고 제56호", hit.get()[1]);
    }

    /**
     * 일련번호를 비워 발행한 고시번호도 인식되는지 검증한다(제목 유실 회귀).
     *
     * <p>실입력 147건이 이 형태다. 숫자를 요구하면 이 줄이 고시번호로 안 잡히고,
     * 그러면 "고시번호 다음 줄"이라는 제목 폴백까지 통째로 죽어 제목·공고종류가 함께 빈다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "인천지방해양항만청 고시 제2008- 호",
            "인천지방해양항만청 고시 제2008-호",
            "인천지방해양수산청 고시 제2007-   호",
    })
    void splitsNoticeNoWithBlankSerial(String line) {
        Optional<String[]> hit = Heuristics.agencyAndNoticeNo(line);
        assertTrue(hit.isPresent(), line);
        assertTrue(hit.get()[0].endsWith("청"), hit.get()[0]);
        assertTrue(hit.get()[1].matches("고시 제200[78]-호"), hit.get()[1]);
    }

    /** 허가증 서식(한 셀에 번호와 제목이 줄바꿈으로)에서 제목을 뽑는지 검증한다. */
    @Test
    void guessesTitleFromNumberLineInsideCell() {
        RawTable table = Tables.gridToTable(List.of(
                List.of("", "", ""),
                List.of("허가번호 제2017-48호\n\n공유수면 점용ㆍ사용 변경허가증",
                        "허가번호 제2017-48호\n\n공유수면 점용ㆍ사용 변경허가증",
                        "허가번호 제2017-48호\n\n공유수면 점용ㆍ사용 변경허가증")));
        assertEquals("공유수면 점용ㆍ사용 변경허가증",
                Heuristics.guessTitleFromTables(List.of(table)));
    }

    /** 번호 줄 다음이 라벨·날짜면 제목으로 삼지 않는지 검증한다(문장 오탐 가드). */
    @Test
    void cellTitleGuessRejectsLabelAndDateLines() {
        RawTable label = Tables.gridToTable(List.of(
                List.of("고시 제2026-1호\n1. 허가번호 : 제2026-9호")));
        assertNull(Heuristics.guessTitleFromTables(List.of(label)));

        RawTable date = Tables.gridToTable(List.of(
                List.of("승인번호 제2026-1호\n2026. 6. 19.")));
        assertNull(Heuristics.guessTitleFromTables(List.of(date)));
    }

    /**
     * "고 시 문" 머리글 아래의 "기관명 제N호"도 고시번호로 잡는지 검증한다.
     *
     * <p>평택청 서식은 고시·공고 낱말이 번호 줄이 아니라 그 앞 줄에 있다. 앞 줄이 머리글일
     * 때만 허용해야 "제N호"로 끝나는 아무 줄이나 고시번호로 오인하지 않는다.
     */
    @Test
    void splitsNoticeNoUnderDocumentHeader() {
        Optional<String[]> hit =
                Heuristics.agencyAndNoticeNo("고   시   문", "평택지방해양수산청  제2018 - 2호");
        assertTrue(hit.isPresent());
        assertEquals("평택지방해양수산청", hit.get()[0]);
        assertEquals("고시 제2018-2호", hit.get()[1]);

        assertTrue(Heuristics.agencyAndNoticeNo("고   시   문(안)", "평택지방해양수산청 제2016-62호")
                .isPresent());
    }

    /** 앞 줄이 머리글이 아니면 "기관명 제N호"를 고시번호로 보지 않는지 검증한다. */
    @Test
    void bareNoticeNoNeedsADocumentHeaderAbove() {
        assertTrue(Heuristics.agencyAndNoticeNo("아무 문장", "평택지방해양수산청  제2018 - 2호")
                .isEmpty());
        assertTrue(Heuristics.agencyAndNoticeNo(null, "평택지방해양수산청  제2018 - 2호")
                .isEmpty());
        assertTrue(Heuristics.agencyAndNoticeNo("고   시   문", "1. 허가번호 : 제2018-2호")
                .isEmpty());
    }

    /** 자간 벌림 텍스트는 복원하고 일반 문장은 건드리지 않는지 검증한다. */
    @Test
    void collapsesLetterSpacedTextOnly() {
        assertEquals("방치선박 제거공고", Heuristics.collapseSpacedText("방 치 선 박  제 거 공 고"));
        assertEquals("공유수면 점용·사용 허가 고시",
                Heuristics.collapseSpacedText("공유수면 점용·사용 허가 고시"));
    }

    /** 직함으로 끝나는 단독 문단을 고시자로 판별하는지 검증한다. */
    @Test
    void detectsSigner() {
        assertTrue(Heuristics.looksLikeSigner("군산지방해양수산청장"));
        assertTrue(Heuristics.looksLikeSigner("인천광역시장"));
        assertFalse(Heuristics.looksLikeSigner("공유수면 점용·사용 변경허가 고시"));
    }

    /** 직함 뒤에 도장 자리 특수문자가 붙어도 고시자로 판별·정리되는지 검증한다(방치선박 공고 회귀). */
    @Test
    void detectsSignerWithTrailingStampGlyph() {
        String line = "새만금개발청장  󰃡";
        assertTrue(Heuristics.looksLikeSigner(line));
        assertEquals("새만금개발청장", Heuristics.cleanSigner(line));
    }
}
