package com.onnara.extract.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Labels} 라벨:값 스캔과 열거 번호 판별 단위 테스트. */
class LabelsTest {

    /** 머리기호·번호가 붙은 라벨 줄을 라벨 줄로 보는지 검증한다. */
    @Test
    void detectsLabelLines() {
        assertTrue(Labels.isLabelLine("1. 허가연월일 : 2026. 6. 5."));
        assertTrue(Labels.isLabelLine("- 위  치 : 인천시 중구"));
        assertFalse(Labels.isLabelLine("공유수면 점용·사용 변경허가 고시"));
    }

    /** 줄 앞 열거 번호를 뽑고, 번호가 없으면 0인지 검증한다. */
    @Test
    void readsLeadingEnumerationNumber() {
        assertEquals(1, Labels.numberOf("1. 승인연월일 : 2020. 12. 14."));
        assertEquals(6, Labels.numberOf("6) 점용·사용 승인을 받은 자"));
        assertEquals(2, Labels.numberOf("- 2. 주    소 : 군산시 시청로 17"));
        assertEquals(0, Labels.numberOf("가. 주    소 : 군산시 시청로 17"));
        assertEquals(0, Labels.numberOf("공유수면 점용·사용 승인 고시"));
        assertEquals(0, Labels.numberOf(null));
    }

    /**
     * {@code scanNumbered}가 {@code scan}과 같은 쌍을 내되 번호를 함께 다는지 검증한다.
     *
     * <p>둘이 갈라지면 처분 블록 분할만 조용히 다른 입력을 보게 된다.
     */
    @Test
    void scanNumberedMatchesScanPairs() {
        List<String> lines = List.of(
                "1. 승인연월일 : 2020. 12. 14.",
                "2. 점용·사용목적 : 부유식 해상 풍황계측기 설치",
                "본문 문장입니다");

        List<Labels.Pair> pairs = Labels.scan(lines);
        List<Labels.Numbered> numbered = Labels.scanNumbered(lines);

        assertEquals(2, pairs.size());
        assertEquals(pairs.size(), numbered.size());
        for (int i = 0; i < pairs.size(); i++) {
            assertEquals(pairs.get(i).label(), numbered.get(i).label());
            assertEquals(pairs.get(i).value(), numbered.get(i).value());
        }
        assertEquals(1, numbered.get(0).number());
        assertEquals(2, numbered.get(1).number());
    }

    /** 값이 빈 라벨 줄이 다음 줄을 값으로 소비할 때도 번호는 라벨 줄에서 온다. */
    @Test
    void keepsLabelLineNumberWhenValueComesFromNextLine() {
        List<Labels.Numbered> pairs = Labels.scanNumbered(List.of(
                "3. 점용·사용의 장소 :",
                "전남 광양시 금호동 863번지선"));
        assertEquals(1, pairs.size());
        assertEquals("전남 광양시 금호동 863번지선", pairs.get(0).value());
        assertEquals(3, pairs.get(0).number());
    }
}
