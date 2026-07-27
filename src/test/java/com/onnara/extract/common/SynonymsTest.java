package com.onnara.extract.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** 샘플에서 관측돼 사전에 보강한 라벨들이 매핑되는지 검증한다. */
    @Test
    void canonicalForResolvesAugmentedLabels() {
        // 승인사항 고시(부산 강서구) 목록표 헤더
        assertEquals(Optional.of("applicant_name"), Synonyms.canonicalFor("피승인자"));
        assertEquals(Optional.of("approval_no"), Synonyms.canonicalFor("승인번호\n(연월일)"));
        assertEquals(Optional.of(Synonyms.WORK_PERIOD), Synonyms.canonicalFor("기간"));
        // 허가 고시문(부산 강서구) 목록표 헤더
        assertEquals(Optional.of("applicant_name"), Synonyms.canonicalFor("대상자"));
        assertEquals(Optional.of("location"), Synonyms.canonicalFor("점용지번"));
        assertEquals(Optional.of("work_description"), Synonyms.canonicalFor("점용목적"));
        // 고시양식(태항조선) 2열 표 라벨
        assertEquals(Optional.of("applicant_name"), Synonyms.canonicalFor("피허가자 성명"));
        assertEquals(Optional.of("applicant_address"), Synonyms.canonicalFor("피허가자 주소"));
        // 방치선박 제거공고 서식
        assertEquals(Optional.of("location"), Synonyms.canonicalFor("발견장소"));
    }

    /** 사전에 없는 라벨·빈값·null은 empty로 처리되는지 검증한다. */
    @Test
    void unknownLabelIsEmpty() {
        assertTrue(Synonyms.canonicalFor("사업비").isEmpty());
        assertTrue(Synonyms.canonicalFor("").isEmpty());
        assertTrue(Synonyms.canonicalFor(null).isEmpty());
    }

    /**
     * 사전 파일의 동의어는 사람이 읽는 형태(띄어쓰기·가운뎃점 포함)로 적고
     * 로드 시 정규화된다 — 편집자가 정규화 규칙을 몰라도 되게 하기 위함이다.
     */
    @Test
    void fileSynonymsAreNormalizedOnLoad() {
        Synonyms.FieldSpec applicant = Synonyms.field("applicant_name").orElseThrow();
        assertTrue(applicant.rawSynonyms().contains("허가를 받은 자"), "원문 형태가 보존돼야 함");
        assertTrue(applicant.synonyms().contains("허가를받은자"), "정규화 형태로 매칭돼야 함");
        assertEquals(Optional.of("applicant_name"), Synonyms.canonicalFor("허가를 받은 자"));
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

    /** 검토 문서가 비지 않도록, 모든 필드가 표시명·설명·DB 컬럼·동의어를 갖추게 한다. */
    @Test
    void everyFieldCarriesReviewMetadata() {
        for (Synonyms.FieldSpec field : Synonyms.fields()) {
            assertFalse(field.display().isBlank(), field.canonical() + ": 표시명 없음");
            assertFalse(field.description().isBlank(), field.canonical() + ": 설명 없음");
            assertFalse(field.dbColumn().isBlank(), field.canonical() + ": DB 컬럼 없음");
            assertFalse(field.synonyms().isEmpty(), field.canonical() + ": 동의어 없음");
        }
        assertFalse(Synonyms.version().isBlank(), "사전 버전이 있어야 함");
    }

    /** 기간만 가상 필드이고, 나머지는 documents 컬럼과 이름이 일치해야 한다. */
    @Test
    void onlyWorkPeriodIsVirtual() {
        for (Synonyms.FieldSpec field : Synonyms.fields()) {
            if (field.canonical().equals(Synonyms.WORK_PERIOD)) {
                assertTrue(field.virtual(), "기간은 가상 필드여야 함");
            } else {
                assertFalse(field.virtual(), field.canonical() + ": 가상 필드가 아니어야 함");
                assertEquals(field.canonical(), field.dbColumn(),
                        field.canonical() + ": 표준 필드명과 DB 컬럼명이 달라짐");
            }
        }
    }
}
