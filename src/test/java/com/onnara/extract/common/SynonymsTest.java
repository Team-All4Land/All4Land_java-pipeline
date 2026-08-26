package com.onnara.extract.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /** 다양한 가운뎃점(ㆍ ․ ‧ 등)이 하나(·)로 통일되는지 검증한다. */
    @Test
    void normalizeUnifiesMiddleDots() {
        assertEquals("점용·사용기간", Synonyms.normalizeLabel("점용ㆍ사용 기간"));
        // U+2024(ONE DOT LEADER) — 실제 HML 샘플(태항조선)의 "점용․사용 장소" 표기
        assertEquals("점용·사용장소", Synonyms.normalizeLabel("점용․사용 장소"));
        assertEquals("점용·사용면적", Synonyms.normalizeLabel("점용‧사용 면적"));
        // OCR이 가운뎃점을 마침표/쉼표로 읽는 경우도 통일된다
        assertEquals("점용·사용장소", Synonyms.normalizeLabel("점용.사용 장소"));
        assertEquals("점용·사용목적", Synonyms.normalizeLabel("점용,사용 목적"));
        // 한글 사이가 아닌 마침표(날짜·번호)는 건드리지 않는다
        assertEquals("2026.6.11", Synonyms.normalizeLabel("2026. 6. 11"));
    }

    /** 괄호 병기 라벨이 두 부분으로 분리되는지 검증한다. */
    @Test
    void splitParentheticalSeparatesParts() {
        assertEquals(List.of("성명", "상호"), Synonyms.splitParenthetical("성명(상호)"));
        assertEquals(List.of("면적"), Synonyms.splitParenthetical("면적"));
    }

    /** 실제 고시문 라벨들이 올바른 itemCd 필드로 매핑되는지 검증한다. */
    @Test
    void canonicalForResolvesSampleLabels() {
        assertEquals(Optional.of("APPROVAL_DATE"), Synonyms.canonicalFor("1. 허가연월일"));
        assertEquals(Optional.of("LOCATION"), Synonyms.canonicalFor("3. 점용·사용의 장소"));
        assertEquals(Optional.of("AREA"), Synonyms.canonicalFor("4. 점용·사용의 면적"));
        assertEquals(Optional.of(Synonyms.WORK_PERIOD), Synonyms.canonicalFor("5 점용·사용의 기간"));
        assertEquals(Optional.of("APPLICANT_NAME"), Synonyms.canonicalFor("나. 성    명"));
        assertEquals(Optional.of("APPLICANT_ADDRESS"), Synonyms.canonicalFor("가. 주    소"));
        assertEquals(Optional.of("APPLICANT_NAME"), Synonyms.canonicalFor("성명(상호)"));
    }

    /**
     * 예전 {@code work_description} 하나로 뭉쳐 두던 라벨들이 워크북 기준 별개 표준항목으로
     * 갈라졌는지 확인한다 — 목적·사업내용·공사명칭·공사종류는 값의 역할이 서로 다르다.
     */
    @Test
    void purposeAndBusinessContentAndConstructionNameAreSeparateFields() {
        assertEquals(Optional.of("PURPOSE"), Synonyms.canonicalFor("3. 점용·사용의 목적"));
        assertEquals(Optional.of("BUSINESS_CONTENT"), Synonyms.canonicalFor("공사내용"));
        assertEquals(Optional.of("CONSTRUCTION_NAME"), Synonyms.canonicalFor("공사명칭"));
        assertEquals(Optional.of("CONSTRUCTION_TYPE"), Synonyms.canonicalFor("공사의 종류"));
        assertEquals(Optional.of("CONSTRUCTION_PERIOD"), Synonyms.canonicalFor("공사기간"));
        assertEquals(Optional.of("CONSTRUCTION_AREA"), Synonyms.canonicalFor("공사면적"));
        assertEquals(Optional.of("COMPLETION_AREA"), Synonyms.canonicalFor("준공면적"));
        assertEquals(Optional.of("OPERATOR_NAME"), Synonyms.canonicalFor("사업시행자"));
    }

    /** 샘플에서 관측돼 사전에 보강한 라벨들이 매핑되는지 검증한다. */
    @Test
    void canonicalForResolvesAugmentedLabels() {
        // 승인사항 고시(부산 강서구) 목록표 헤더
        assertEquals(Optional.of("APPLICANT_NAME"), Synonyms.canonicalFor("피승인자"));
        assertEquals(Optional.of("APPROVAL_NO"), Synonyms.canonicalFor("승인번호\n(연월일)"));
        assertEquals(Optional.of(Synonyms.WORK_PERIOD), Synonyms.canonicalFor("기간"));
        // 허가 고시문(부산 강서구) 목록표 헤더
        assertEquals(Optional.of("APPLICANT_NAME"), Synonyms.canonicalFor("대상자"));
        assertEquals(Optional.of("LOCATION"), Synonyms.canonicalFor("점용지번"));
        assertEquals(Optional.of("PURPOSE"), Synonyms.canonicalFor("점용목적"));
        // 고시양식(태항조선) 2열 표 라벨
        assertEquals(Optional.of("APPLICANT_NAME"), Synonyms.canonicalFor("피허가자 성명"));
        assertEquals(Optional.of("APPLICANT_ADDRESS"), Synonyms.canonicalFor("피허가자 주소"));
        // 방치선박 제거공고 서식
        assertEquals(Optional.of("LOCATION"), Synonyms.canonicalFor("발견장소"));
    }

    /**
     * 사전에 없는 라벨·빈값·null은 empty로 처리되는지 검증한다.
     *
     * <p>'관리번호'·'선적항'은 방치선박 제거공고 서식의 별개 필드로, 표준항목 어디에도
     * 해당하지 않아 일부러 등재하지 않았다 — extras로 보존돼 사전 보강 후보가 된다.
     */
    @Test
    void unknownLabelIsEmpty() {
        assertTrue(Synonyms.canonicalFor("관리번호").isEmpty());
        assertTrue(Synonyms.canonicalFor("선적항").isEmpty());
        assertTrue(Synonyms.canonicalFor("").isEmpty());
        assertTrue(Synonyms.canonicalFor(null).isEmpty());
    }

    /**
     * 사전 파일의 동의어는 사람이 읽는 형태(띄어쓰기·가운뎃점 포함)로 적고
     * 로드 시 정규화된다 — 편집자가 정규화 규칙을 몰라도 되게 하기 위함이다.
     */
    @Test
    void fileSynonymsAreNormalizedOnLoad() {
        Synonyms.FieldSpec applicant = Synonyms.field("APPLICANT_NAME").orElseThrow();
        assertTrue(applicant.rawSynonyms().contains("허가를 받은 자"), "원문 형태가 보존돼야 함");
        assertTrue(applicant.synonyms().contains("허가를받은자"), "정규화 형태로 매칭돼야 함");
        assertEquals(Optional.of("APPLICANT_NAME"), Synonyms.canonicalFor("허가를 받은 자"));
    }

    /**
     * 같은 라벨이 두 필드에 등재되면 어느 쪽으로 매핑될지가 등재 순서에 좌우된다.
     * 로드 시 중단시키는 규칙이 실제로 사전을 지키고 있는지, 중복이 없음을 확인한다.
     */
    @Test
    void noSynonymIsRegisteredUnderTwoFields() {
        List<String> all = Synonyms.fields().stream()
                .flatMap(f -> f.synonyms().stream())
                .toList();
        assertEquals(all.size(), Set.copyOf(all).size(), "정규화 후 중복 등재된 동의어가 있음");
    }

    /**
     * 검토 문서가 비지 않도록 모든 필드가 표시명·설명·저장 계층을 갖추게 한다.
     *
     * <p>동의어가 빈 필드는 허용하되 사유({@code notes})를 반드시 적게 한다 — 라벨로는
     * 구별할 수 없는 표준항목이 실제로 있고(subject_address), 실수로 빠뜨린 것과
     * 의도적으로 비운 것을 갈라 놓아야 한다.
     */
    @Test
    void everyFieldCarriesReviewMetadata() {
        for (Synonyms.FieldSpec field : Synonyms.fields()) {
            assertFalse(field.itemNm().isBlank(), field.itemCd() + ": 표시명 없음");
            assertFalse(field.description().isBlank(), field.itemCd() + ": 설명 없음");
            assertTrue(Synonyms.SCOPE_ATTACHMENT.equals(field.scope())
                            || Synonyms.SCOPE_ATTRIBUTE.equals(field.scope()),
                    field.itemCd() + ": 저장 계층이 attachment/attribute가 아님");
            assertFalse(field.valTyCd().isBlank(), field.itemCd() + ": 값 형식 없음");
            if (field.synonyms().isEmpty()) {
                assertNotNull(field.notes(),
                        field.itemCd() + ": 동의어가 비었으면 사유를 notes에 적어야 함");
            }
        }
        assertFalse(Synonyms.version().isBlank(), "사전 버전이 있어야 함");
    }

    /** 기간만 가상 필드다 — Mapper가 start/end로 분리하는 유일한 항목. */
    @Test
    void onlyWorkPeriodIsVirtual() {
        for (Synonyms.FieldSpec field : Synonyms.fields()) {
            if (field.itemCd().equals(Synonyms.WORK_PERIOD)) {
                assertTrue(field.virtual(), "기간은 가상 필드여야 함");
            } else {
                assertFalse(field.virtual(), field.itemCd() + ": 가상 필드가 아니어야 함");
            }
        }
    }

    /**
     * 워크북 전수 통계가 확정한 구성 — 문서 메타 5 + 표준항목 40.
     *
     * <p>주요 항목 6개는 전역 출현율 60% 이상이며, 이 집합이 바뀌면 누락 검증의 기준선이
     * 함께 바뀌므로 사전을 손볼 때 의식적으로 고치게 못 박아 둔다.
     */
    @Test
    void dictionaryMatchesWorkbookComposition() {
        List<Synonyms.FieldSpec> attributes = Synonyms.fields().stream()
                .filter(Synonyms.FieldSpec::isAttribute).toList();
        assertEquals(40, attributes.size(), "표준항목은 40종이어야 함");
        assertEquals(5, Synonyms.fields().size() - attributes.size(), "문서 메타는 5종이어야 함");

        List<String> core = attributes.stream()
                .filter(Synonyms.FieldSpec::coreYn).map(Synonyms.FieldSpec::itemCd).sorted().toList();
        assertEquals(List.of("APPLICANT_ADDRESS", "APPLICANT_NAME", "AREA",
                "LOCATION", "PURPOSE", "WORK_PERIOD"), core, "주요 항목 6종이 달라짐");
    }

    /**
     * 정규화가 머리기호를 흡수해야 한다 — 전수 표본에 ○ 170건, - 118건, □ 64건,
     * ∙ 17건, ․ 10건으로 나타난다. 흡수하지 못하면 같은 라벨이 사전에 여러 번 등재돼야 한다.
     */
    @Test
    void normalizationAbsorbsLeadingBulletSymbols() {
        for (String prefixed : List.of("○ 면적", "□ 면적", "※ 면적", "◦ 면적", "▷ 면적",
                "∙면적", "․면적", "‧면적", "- 면적", "· 면적", "□ ○ 면적")) {
            assertEquals(Synonyms.normalizeLabel("면적"), Synonyms.normalizeLabel(prefixed),
                    "머리기호가 흡수되지 않음: " + prefixed);
        }
        // 머리기호만 벗기고 라벨 본문은 건드리지 않는다
        assertEquals(Synonyms.normalizeLabel("허가면적"), Synonyms.normalizeLabel("□ 허가면적"));
    }
}
