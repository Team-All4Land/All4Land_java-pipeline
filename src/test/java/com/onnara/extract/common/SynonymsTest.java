package com.onnara.extract.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Synonyms} 라벨 정규화·동의어 매핑 단위 테스트. */
class SynonymsTest {

    /** 선행 번호·공백·후행 콜론이 정규화 과정에서 제거되는지 검증한다. */
    @Test
    void normalizeStripsNumberingWhitespaceAndColon() {
        assertEquals("점용·사용의장소", Synonyms.normalizeLabel("3. 점용·사용의 장소 :"));
        assertEquals("주소", Synonyms.normalizeLabel("가. 주    소"));
        assertEquals("위치", Synonyms.normalizeLabel("- 위  치 ："));
        assertEquals("면적", Synonyms.normalizeLabel("① 면적"));
    }

    /** 다양한 가운뎃점(ㆍ 등)이 하나(·)로 통일되는지 검증한다. */
    @Test
    void normalizeUnifiesMiddleDots() {
        assertEquals("점용·사용기간", Synonyms.normalizeLabel("점용ㆍ사용 기간"));
    }

    /** 괄호 병기 라벨이 두 부분으로 분리되는지 검증한다. */
    @Test
    void splitParentheticalSeparatesParts() {
        assertEquals(List.of("성명", "상호"), Synonyms.splitParenthetical("성명(상호)"));
        assertEquals(List.of("면적"), Synonyms.splitParenthetical("면적"));
    }

    /** 실제 고시문 라벨들이 올바른 canonical 필드로 매핑되는지 검증한다. */
    @Test
    void canonicalForResolvesSampleLabels() {
        assertEquals(Optional.of("approval_date"), Synonyms.canonicalFor("1. 허가연월일"));
        assertEquals(Optional.of("location"), Synonyms.canonicalFor("3. 점용·사용의 장소"));
        assertEquals(Optional.of("area"), Synonyms.canonicalFor("4. 점용·사용의 면적"));
        assertEquals(Optional.of(Synonyms.WORK_PERIOD), Synonyms.canonicalFor("5 점용·사용의 기간"));
        assertEquals(Optional.of("applicant_name"), Synonyms.canonicalFor("나. 성    명"));
        assertEquals(Optional.of("applicant_address"), Synonyms.canonicalFor("가. 주    소"));
        assertEquals(Optional.of("work_description"), Synonyms.canonicalFor("공사명칭"));
        assertEquals(Optional.of("applicant_name"), Synonyms.canonicalFor("성명(상호)"));
    }

    /** 사전에 없는 라벨·빈값·null은 empty로 처리되는지 검증한다. */
    @Test
    void unknownLabelIsEmpty() {
        assertTrue(Synonyms.canonicalFor("사업비").isEmpty());
        assertTrue(Synonyms.canonicalFor("").isEmpty());
        assertTrue(Synonyms.canonicalFor(null).isEmpty());
    }
}
