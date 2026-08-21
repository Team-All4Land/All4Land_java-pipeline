package com.onnara.extract.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SourceFileName} 파일명 → 게시물·첨부 순번 파싱 단위 테스트. */
class SourceFileNameTest {

    /** 실제 크롤 산출물 파일명에서 순번과 첨부순번을 읽어내야 한다. */
    @Test
    void parsesCrawledFileNames() {
        SourceFileName.Parsed first = SourceFileName.parse(
                "6034_1_5 공유수면 점용 사용 허가 고시문(소라면 사곡리 1291).hwp");
        assertEquals(6034, first.notiSn());
        assertEquals(1, first.atchSn());
        assertTrue(first.crawled());

        SourceFileName.Parsed second = SourceFileName.parse(
                "9999_2_실시계획 승인증(신고확인증)2022-80(2021년 금일 신평항 어촌뉴딜).hwp");
        assertEquals(9999, second.notiSn());
        assertEquals(2, second.atchSn());
    }

    /**
     * 같은 게시물의 첨부들은 순번을 공유하고 첨부순번만 달라야 한다 —
     * 여수시청 6034는 첨부 5개가 각각 다른 지번의 독립 허가 고시문이다.
     */
    @Test
    void attachmentsOfOneNoticeShareTheNoticeNumber() {
        List<String> files = List.of(
                "6034_1_5 공유수면 점용 사용 허가 고시문(소라면 사곡리 1291).hwp",
                "6034_2_4 공유수면 점용 사용 허가 고시문(소라면 관기리 1167).hwp",
                "6034_3_3 공유수면 점용 사용 허가 고시문(돌산읍 신복리 1541).hwp");

        for (int i = 0; i < files.size(); i++) {
            SourceFileName.Parsed parsed = SourceFileName.parse(files.get(i));
            assertEquals(6034, parsed.notiSn(), files.get(i));
            assertEquals(i + 1, parsed.atchSn(), files.get(i));
        }
    }

    /** 제목 없이 순번만 있는 형태도 읽어야 한다. */
    @Test
    void parsesFileNameWithoutTitlePart() {
        SourceFileName.Parsed parsed = SourceFileName.parse("83_1.hml");
        assertEquals(83, parsed.notiSn());
        assertEquals(1, parsed.atchSn());
        assertTrue(parsed.crawled());
    }

    /**
     * 첨부가 1건인 게시물은 첨부순번 없이 {순번}_{제목}으로 온다 — 전수 표본의 다수다.
     *
     * <p>이 형태를 못 읽어 음수 폴백으로 새면서 게시물 1,746건이 통째로 틀어졌다.
     */
    @Test
    void parsesSingleAttachmentFilesWithoutAnAttachNumber() {
        SourceFileName.Parsed parsed = SourceFileName.parse(
                "1000_해동마린 공유수면 점사용 변경(기간연장)허가 고시문.hwp");
        assertEquals(1000, parsed.notiSn());
        assertEquals(1, parsed.atchSn());
        assertTrue(parsed.crawled());

        // 제목 중간·끝에 숫자가 붙어도 순번은 앞자리 하나다
        SourceFileName.Parsed suffixed = SourceFileName.parse(
                "1823_공유수면 점용ㆍ사용 변경승인 고시[서산시장, 독곶리 569-61]_260422.hml");
        assertEquals(1823, suffixed.notiSn());
        assertEquals(1, suffixed.atchSn());
    }

    /**
     * 제목이 타임스탬프로 시작해도 첨부순번으로 읽으면 안 된다.
     *
     * <p>{@code 1450_20130409174438859_…} 같은 파일이 전수 표본에 134건 있다. 첨부순번 자리를
     * 열어 두면 이 숫자가 첨부순번이 돼 한 게시물이 첨부 2경 번째 자리에 앉는다.
     */
    @Test
    void doesNotMistakeATimestampTitleForAnAttachNumber() {
        SourceFileName.Parsed parsed = SourceFileName.parse(
                "1450_20130409174438859_공유수면_점용사용_변경허가_고시(포스코).hwp");

        assertEquals(1450, parsed.notiSn());
        assertEquals(1, parsed.atchSn(), "타임스탬프는 제목이지 첨부순번이 아니다");
        assertTrue(parsed.crawled());
    }

    /**
     * 첨부순번은 결번일 수 있다 — 1번을 못 받아 온 게시물이 실제로 있다.
     *
     * <p>결번은 버그가 아니라 미수집 신호다. 순번을 다시 매겨 메우면 그 신호가 사라진다.
     */
    @Test
    void keepsGapsInTheAttachSequence() {
        assertEquals(2, SourceFileName.parse("1908_2_각종 서식류.hml").atchSn());
        assertEquals(3, SourceFileName.parse("1908_3_해안선 절대표고 산정서.hml").atchSn());
        assertEquals(4, SourceFileName.parse("1908_4_점용사용료 평가서.hml").atchSn());
    }

    /**
     * 크롤 패턴이 아닌 파일은 첨부 1개짜리 단독 공고로 보고 음수 순번을 발급한다.
     * 음수여야 나중에 크롤 데이터를 같은 DB에 넣어도 순번이 겹치지 않는다.
     */
    @Test
    void fallsBackToNegativeSequenceForNonCrawledFiles() {
        SourceFileName.Parsed parsed = SourceFileName.parse("공유수면 점용사용 허가 고시문.hwp");

        assertFalse(parsed.crawled());
        assertTrue(parsed.notiSn() < 0, "폴백 순번은 음수여야 함: " + parsed.notiSn());
        assertEquals(1, parsed.atchSn());
    }

    /** 폴백 순번은 호출마다 새로 발급돼 서로 다른 파일이 한 게시물로 뭉치지 않아야 한다. */
    @Test
    void fallbackSequenceIsUniquePerCall() {
        SourceFileName.Parsed a = SourceFileName.parse("한글3.0 공유수면 고시(동해시).hwp");
        SourceFileName.Parsed b = SourceFileName.parse("방치선박 제거공고(군산-2).hwpx");
        assertNotEquals(a.notiSn(), b.notiSn());
    }

    /** null·확장자 없음처럼 어긋난 입력도 예외 없이 폴백으로 처리한다. */
    @Test
    void handlesMalformedInput() {
        assertFalse(SourceFileName.parse(null).crawled());
        assertFalse(SourceFileName.parse("").crawled());
        assertFalse(SourceFileName.parse("고시문").crawled());
        // int를 벗어나는 순번은 크롤 산출물이 아니다
        assertFalse(SourceFileName.parse("99999999999_1_고시문.hwp").crawled());
    }
}
