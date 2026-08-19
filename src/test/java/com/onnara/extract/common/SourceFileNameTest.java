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
        assertEquals(6034, first.noticeNo());
        assertEquals(1, first.attachNo());
        assertTrue(first.crawled());

        SourceFileName.Parsed second = SourceFileName.parse(
                "9999_2_실시계획 승인증(신고확인증)2022-80(2021년 금일 신평항 어촌뉴딜).hwp");
        assertEquals(9999, second.noticeNo());
        assertEquals(2, second.attachNo());
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
            assertEquals(6034, parsed.noticeNo(), files.get(i));
            assertEquals(i + 1, parsed.attachNo(), files.get(i));
        }
    }

    /** 제목 없이 순번만 있는 형태도 읽어야 한다. */
    @Test
    void parsesFileNameWithoutTitlePart() {
        SourceFileName.Parsed parsed = SourceFileName.parse("83_1.hml");
        assertEquals(83, parsed.noticeNo());
        assertEquals(1, parsed.attachNo());
        assertTrue(parsed.crawled());
    }

    /**
     * 크롤 패턴이 아닌 파일은 첨부 1개짜리 단독 공고로 보고 음수 순번을 발급한다.
     * 음수여야 나중에 크롤 데이터를 같은 DB에 넣어도 순번이 겹치지 않는다.
     */
    @Test
    void fallsBackToNegativeSequenceForNonCrawledFiles() {
        SourceFileName.Parsed parsed = SourceFileName.parse("공유수면 점용사용 허가 고시문.hwp");

        assertFalse(parsed.crawled());
        assertTrue(parsed.noticeNo() < 0, "폴백 순번은 음수여야 함: " + parsed.noticeNo());
        assertEquals(1, parsed.attachNo());
    }

    /** 폴백 순번은 호출마다 새로 발급돼 서로 다른 파일이 한 게시물로 뭉치지 않아야 한다. */
    @Test
    void fallbackSequenceIsUniquePerCall() {
        SourceFileName.Parsed a = SourceFileName.parse("한글3.0 공유수면 고시(동해시).hwp");
        SourceFileName.Parsed b = SourceFileName.parse("방치선박 제거공고(군산-2).hwpx");
        assertNotEquals(a.noticeNo(), b.noticeNo());
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
