package com.onnara.extract.engine.hwp3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 한글 3.0 문자 코드 변환({@link Hwp3Chars})의 단위 테스트. */
class Hwp3CharsTest {

    /** ASCII는 그대로 통과한다. */
    @Test
    void passesAsciiThrough() {
        assertEquals('A', Hwp3Chars.toUnicode('A'));
        assertEquals('7', Hwp3Chars.toUnicode('7'));
    }

    /**
     * 조합형(KSSM) 한글 — 최상위 비트가 선 코드는 초성·중성·종성 5비트씩이다.
     * 완성형으로 읽으면 전혀 다른 글자가 나오므로, 이 변환이 곧 본문의 정확도다.
     */
    @Test
    void decodesJohabHangul() {
        // 0x8861 = 조합형 '가'
        assertEquals('가', Hwp3Chars.toUnicode(0x8861));
    }

    /** 한자는 0x4000부터의 색인을 완성형 한자 영역으로 환산해 읽는다. */
    @Test
    void decodesHanja() {
        // 색인 0 → 완성형 0xCAA1 = 伽
        assertEquals('伽', Hwp3Chars.toUnicode(0x4000));
    }

    /** 정의 범위를 넘는 한자 색인은 대응이 없어 □로 떨어진다. */
    @Test
    void marksOutOfRangeHanjaAsUnmapped() {
        assertEquals(Hwp3Chars.UNMAPPED, Hwp3Chars.toUnicode(0x4000 + 4888));
    }

    /**
     * 특수문자 — 실제 고시문 본문의 「공유수면 관리 및 매립에 관한 법률」에 쓰인 낫표다.
     * 이 구간을 빠뜨리면 법령명이 □로 깨져 제목·내용 필드가 통째로 훼손된다.
     */
    @Test
    void decodesCornerBracketsUsedInStatuteNames() {
        assertEquals('「', Hwp3Chars.toUnicode(0x3418));
        assertEquals('」', Hwp3Chars.toUnicode(0x3419));
    }

    /** 로고성 구간(0x37C0~)은 완성형을 거치지 않고 낱글자로 되돌린다. */
    @Test
    void decodesHancomLogoRange() {
        assertEquals('한', Hwp3Chars.toUnicode(0x37C0));
        assertEquals('터', Hwp3Chars.toUnicode(0x37C5));
    }

    /** 괘선은 방향만 살려 ASCII 선 문자로 옮긴다 — 값 추출이 장식을 삼키지 않게 한다. */
    @Test
    void mapsLineCharsToAsciiStrokes() {
        String strokes = "-|+";
        for (int code = 0x3013; code < 0x3013 + 11 * 7; code++) {
            char ch = Hwp3Chars.toUnicode(code);
            assertEquals(true, strokes.indexOf(ch) >= 0, "괘선이 아닌 문자: " + ch);
        }
    }

    /** 아는 구간이 아니면 □ — 물음표가 아니어야 본문의 물음표와 구분된다. */
    @Test
    void marksUnknownCodeAsUnmapped() {
        assertEquals(Hwp3Chars.UNMAPPED, Hwp3Chars.toUnicode(0x0200));
    }

    /** 문단 끝·널은 버리고, 그 밖의 제어 문자는 공백으로 바꾼다. */
    @Test
    void dropsParagraphEndAndNulls() {
        int[] codes = {'A', 0, Hwp3Chars.CH_END_PARA, 9, 'B'};

        assertEquals("A B", Hwp3Chars.decode(codes, codes.length));
    }
}
