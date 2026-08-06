package com.onnara.extract.engine.hwp3;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/**
 * 한글 3.0의 16비트 문자 코드(hchar) → 유니코드 변환.
 *
 * <p>한글 3.0은 본문을 <b>조합형(KSSM)</b> 16비트 코드로 저장한다. 완성형(EUC-KR)으로 읽으면
 * 한 글자도 맞지 않는다 — "게재일자"가 "??멸???"처럼 나온다. 초성·중성·종성을 각각 5비트로
 * 쪼개 넣는 조합형은 JDK의 {@code x-Johab} 캐릭터셋과 비트 배치가 같아, 코드를 빅엔디안
 * 2바이트로 풀어 디코드하면 그대로 복원된다. 한자·특수문자는 완성형 코드로 환산한 뒤
 * {@code EUC-KR}로 디코드한다. <b>외부 라이브러리 없이 JDK 기본 캐릭터셋만 쓴다.</b>
 *
 * <p>구간 판정과 환산식은 LibreOffice {@code hwpfilter/source/hcode.cxx}의
 * {@code hcharconv()} UNICODE 경로를 옮긴 것이다(MPL-2.0 / Apache-2.0). 한글 3.0 파일 형식의
 * 유일한 공개 레퍼런스 구현이라, 구간 경계나 상수 표를 임의로 줄이면 곧바로 오변환이 된다.
 *
 * <p>어느 규칙에도 걸리지 않거나 캐릭터셋이 매핑을 갖고 있지 않으면 {@link #UNMAPPED}(□)를
 * 낸다. 물음표가 아니라 □인 것도 레퍼런스와 같다 — 본문에 원래 있던 물음표와 구분되어야
 * "변환 실패가 몇 자인가"를 사후에 셀 수 있다.
 */
public final class Hwp3Chars {

    /** 변환할 수 없는 문자 — LibreOffice와 같은 U+25A1(□). */
    public static final char UNMAPPED = '□';

    /** 문단 끝({@code CH_END_PARA}) 제어 문자 코드. */
    static final int CH_END_PARA = 13;

    /** 특수문자 구간의 시작 — 이 값부터 완성형 기호로 환산한다. */
    private static final int HCA_KSS = 0x3400;

    /** "한글과컴퓨터" 등 로고성 문자 구간의 시작 — 별도 표로 환산한다. */
    private static final int HCA_TG = 0x37C0;

    /** 완성형으로 옮길 수 없는 특수문자 — 레퍼런스의 {@code noneks}. */
    private static final int NONE_KS = 0xA1A1;

    /** 괘선 문자 구간의 시작. */
    private static final int LINE_BASE = 0x3013;

    /** 괘선 문자 구간의 길이 — 11방향 × 7종. */
    private static final int LINE_COUNT = 11 * 7;

    /** 한글 3.0이 정의한 한자 개수 — 이 수를 넘는 색인은 완성형에 대응이 없다. */
    private static final int HANJA_COUNT = 4888;

    /** 완성형 한자·기호의 하위 바이트 범위 크기(0xA1~0xFE). */
    private static final int KS_ROW_SIZE = 0xFE - 0xA1 + 1;

    /**
     * 괘선 문자의 방향 코드 — {@code hcode.cxx}의 {@code index2dir}.
     * 3은 가로선, 12는 세로선, 나머지는 모두 이음매다.
     */
    private static final int[] LINE_DIRECTIONS = {10, 11, 9, 14, 15, 13, 6, 7, 5, 3, 12};

    /**
     * {@value #HCA_TG} 이상 로고성 문자의 완성형 코드 — {@code hcode.cxx}의 {@code tblhhtg_ks}.
     * 앞 6개(한/글/과/컴/퓨/터)만 완성형 한글이고 나머지는 기호다.
     */
    private static final int[] TG_TO_KS = {
            0xC7D1, 0xB1DB, 0xB0FA, 0xC4C4, 0xC7BB, 0xC5CD, NONE_KS, NONE_KS,
            0xA2B1, 0xA3DF, 0xA2D5, 0xA6B1, 0xA1B8, 0xA1B9, 0xA3DF, 0xA1DA,
            0xA2C6, 0xA2CC, 0xA2CB, NONE_KS, NONE_KS, 0xA6BE, 0xA6B9, 0xA6C1,
            0xA6C2, 0xA6B4, 0xA6AD, 0xA6AF, 0xA6B0, 0xA6C3, 0xA6C4, NONE_KS,
            0xA6AE, 0xA6B0, 0xA2D7, 0xA1E1, 0xA1D6, NONE_KS, 0xA6BC, 0xA6B7,
            0xA6B1, 0xA6AE, 0xA6B5, 0xA6B3, 0xA6B2, 0xA6AC, 0xA6B6, 0xA6BA,
            0xA6BF, 0xA6B8, 0xA6BD, 0xA6C5, 0xA6C6, 0xA6C8, 0xA6C7, 0xA6C0,
            0xA6BB, NONE_KS, NONE_KS, NONE_KS, 0xA1E1, 0xA1E1, 0xA1E1, 0xA1E1};

    /**
     * 글머리표 구간({@code 0x2F00}~{@code 0x2F6F})의 16단위 구획별 완성형 시작 코드.
     * 구획 안에서 하위 4비트가 6 이상이면 다음 코드(속 빈 기호)를 쓴다.
     */
    private static final int[] BULLET_TO_KS = {
            0xA1E0, 0xA1DB, 0xA1DE, 0xA1E2, 0xA1E4, 0xA2B7, 0xA2B9};

    /** 조합형 코드(0x8000~0xFFFF) → 유니코드. 매핑이 없으면 0. */
    private static final char[] JOHAB_TABLE = buildTable("x-Johab");

    /** 완성형 코드(0x8000~0xFFFF) → 유니코드. 매핑이 없으면 0. */
    private static final char[] KS_TABLE = buildTable("EUC-KR");

    /** 인스턴스화 방지 — 정적 변환 함수만 제공하는 유틸리티 클래스. */
    private Hwp3Chars() {
    }

    /**
     * 문자 코드 목록을 문자열로 변환한다. 문단 끝·널 문자는 버리고, 그 밖의 제어 문자는
     * 공백으로 바꾼다(표 셀 텍스트가 제어 문자로 붙어 버리지 않게).
     */
    public static String decode(int[] codes, int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int code = codes[i];
            if (code == 0 || code == CH_END_PARA) {
                continue;
            }
            if (code < 32) {
                out.append(' ');
                continue;
            }
            out.append(toUnicode(code));
        }
        return out.toString();
    }

    /**
     * 문자 코드 하나를 유니코드 문자로 변환한다.
     *
     * <p>판정 순서가 곧 규칙이다 — 조합형 한글(최상위 비트)을 먼저 걸러야 한자 구간
     * ({@code 0x4000} 비트)과 겹치지 않는다.
     */
    public static char toUnicode(int code) {
        int ch = code & 0xFFFF;
        if (ch < 128) {
            return (char) ch;
        }
        if ((ch & 0x8000) != 0) {
            return fromTable(JOHAB_TABLE, ch);
        }
        if ((ch & 0x4000) != 0) {
            return hanja(ch);
        }
        if (isLineChar(ch)) {
            return lineChar(ch);
        }
        if (0x2F00 <= ch && ch <= 0x2F6F && (ch & 0x0F) < 9) {
            return bullet(ch);
        }
        return special(ch);
    }

    /**
     * 한자 — 한글 3.0은 {@code 0x4000}부터 {@value #HANJA_COUNT}자를 자체 순서로 담는다.
     * 완성형 한자 영역(상위 0xCA~, 하위 0xA1~0xFE)으로 환산해 디코드한다.
     */
    private static char hanja(int ch) {
        int index = ch - 0x4000;
        if (index < 0 || index >= HANJA_COUNT) {
            // 한글 3.0이 자체 정의한 한자로, 완성형·유니코드에 대응이 없다
            return UNMAPPED;
        }
        int hi = index / KS_ROW_SIZE + 0xCA;
        int lo = index % KS_ROW_SIZE + 0xA1;
        return fromTable(KS_TABLE, (hi << 8) | lo);
    }

    /** 괘선 문자 구간인지. */
    private static boolean isLineChar(int ch) {
        return LINE_BASE <= ch && ch < LINE_BASE + LINE_COUNT;
    }

    /**
     * 괘선 문자 — 방향만 살려 ASCII 선 문자로 옮긴다.
     *
     * <p>고시문에서 괘선은 표를 흉내 낸 장식이라 모양을 그대로 살릴 이유가 없다. 오히려
     * 유니코드 박스드로잉으로 옮기면 라벨:값 추출이 이 문자들을 값의 일부로 집어삼킨다.
     */
    private static char lineChar(int ch) {
        int direction = LINE_DIRECTIONS[(ch - LINE_BASE) % LINE_DIRECTIONS.length];
        return switch (direction) {
            case 3 -> '-';
            case 12 -> '|';
            default -> '+';
        };
    }

    /** 글머리표 — 16단위 구획으로 완성형 기호를 고르고, 하위 4비트가 6 이상이면 속 빈 기호. */
    private static char bullet(int ch) {
        int slot = Math.min((ch - 0x2F00) / 0x10, BULLET_TO_KS.length - 1);
        int ks = BULLET_TO_KS[slot];
        if ((ch & 0x0F) >= 6) {
            ks++;
        }
        return fromTable(KS_TABLE, ks);
    }

    /**
     * 특수문자 — {@link #hangulToKs} 환산 뒤 완성형으로 디코드한다.
     *
     * <p>{@value #HCA_TG}~{@code 0x37C5}는 "한글과컴퓨터" 로고를 한 글자씩 담은 구간이라
     * 완성형을 거치지 않고 곧바로 유니코드로 옮긴다(완성형 환산표는 이 여섯 자를 낱글자로
     * 되돌리지 못한다). {@code 0x309B}/{@code 0x309D}는 반각 낫표다.
     */
    private static char special(int ch) {
        if (HCA_TG <= ch && ch <= HCA_TG + 5) {
            return "한글과컴퓨터".charAt(ch - HCA_TG);
        }
        int ks = hangulToKs(ch);
        if (ks == 0) {
            return UNMAPPED;
        }
        if (ks < 128) {
            return (char) ks;
        }
        if (ks == 0x2020) {
            return switch (ch) {
                case 0x309B -> '｢';
                case 0x309D -> '｣';
                default -> UNMAPPED;
            };
        }
        return fromTable(KS_TABLE, ks);
    }

    /**
     * 한글 3.0 특수문자 코드 → 완성형(KS X 1001) 코드 — {@code hcode.cxx}의 {@code s_hh2ks}.
     * 대응이 없으면 0, 구간 밖이면 {@code 0x2020}(호출부가 □로 바꾼다)을 낸다.
     */
    private static int hangulToKs(int hh) {
        if (hh == 0x81 || hh == 0x82) {
            return '"';
        }
        if (hh == 0x83 || hh == 0x84) {
            return '\'';
        }
        int page = hh >> 8;
        if (page == 0x1F) {
            int row = 170;
            int cell = hh & 0xFF;
            if (cell >= 0x60) {
                row++;
                cell -= 0x60;
            }
            return (row << 8) | (cell + 160);
        }
        if ((hh & 0xFF) >= 0xC0 || hh == 0x1F00) {
            return 0;
        }
        if (page < 0x34 || page >= 0x38) {
            return 0x2020;
        }
        if (hh >= HCA_TG) {
            return TG_TO_KS[hh - HCA_TG];
        }
        int offset = hh - HCA_KSS;
        int row = offset / 0x60 + 161;
        int cell = offset % 0x60 + 160;
        if (row == 170) {
            row += 2;
        }
        return (row << 8) | cell;
    }

    /** 변환표에서 코드를 찾는다. 표 밖이거나 매핑이 없으면 □. */
    private static char fromTable(char[] table, int code) {
        int index = code - 0x8000;
        if (index < 0 || index >= table.length) {
            return UNMAPPED;
        }
        char ch = table[index];
        return ch == 0 ? UNMAPPED : ch;
    }

    /**
     * 캐릭터셋의 2바이트 코드 전 구간(0x8000~0xFFFF)을 한 번에 디코드해 표로 만든다.
     *
     * <p>글자마다 디코더를 돌리면 수만 건 배치에서 디코더 생성·리셋 비용이 본문 길이만큼
     * 곱해진다. 표는 64KB짜리 하나뿐이고 클래스 로딩 때 한 번만 만든다.
     */
    private static char[] buildTable(String charsetName) {
        char[] table = new char[0x8000];
        CharsetDecoder decoder = Charset.forName(charsetName).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        byte[] pair = new byte[2];
        for (int code = 0x8000; code <= 0xFFFF; code++) {
            pair[0] = (byte) (code >> 8);
            pair[1] = (byte) code;
            decoder.reset();
            try {
                CharBuffer decoded = decoder.decode(ByteBuffer.wrap(pair));
                // 한 코드가 두 글자로 풀리는 경우는 표로 다룰 수 없다 — 미매핑으로 남긴다
                if (decoded.length() == 1) {
                    table[code - 0x8000] = decoded.charAt(0);
                }
            } catch (CharacterCodingException e) {
                // 이 코드는 해당 완성형/조합형에 없다 — 0으로 남겨 □가 되게 한다
            }
        }
        return table;
    }
}
