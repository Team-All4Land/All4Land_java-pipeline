package com.onnara.extract.common;

import com.onnara.extract.common.model.RawCell;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;
import com.onnara.extract.common.model.SchemaResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 본문 분량 측정({@link DocumentSize})과 적재 규칙({@link LoadPolicy}) 단위 테스트. */
class LoadPolicyTest {

    /** 본문 글자 수는 문단과 표 셀을 합쳐 세고, 공백은 분량으로 치지 않는다. */
    @Test
    void countsParagraphsAndTableCellsWithoutWhitespace() {
        RawDocument raw = new RawDocument("고시문.hml", "hml", false);
        raw.getContent().add(new RawParagraph("공유수면 점용"));          // 공백 제외 6자
        raw.getContent().add(new RawTable(1, 2, null,
                List.of(List.of("관 리 청", "군산"))));                    // 3 + 2 = 5자

        assertEquals(11, DocumentSize.bodyCharCnt(raw));
    }

    /** content가 비어 있어도 0을 돌려주고 터지지 않아야 한다. */
    @Test
    void handlesEmptyAndNullDocuments() {
        assertEquals(0, DocumentSize.bodyCharCnt(null));
        assertEquals(0, DocumentSize.bodyCharCnt(new RawDocument("빈.pdf", "pdf", false)));
    }

    /**
     * 실측한 고시문 분량(236~2,357자)은 기본 임계에서 전부 적재 대상으로 남아야 한다 —
     * 이 판정의 대가는 비대칭이라 고시문을 빠뜨리는 쪽이 훨씬 나쁘다.
     */
    @Test
    void keepsOrdinaryNoticesLoadable() {
        LoadPolicy policy = new LoadPolicy(LoadPolicy.DEFAULT_MAX_BODY_CHARS);

        for (int chars : new int[]{236, 504, 2_357}) {
            RawDocument raw = document("고시문.hml", chars);
            SchemaResult schema = policy.apply(schemaFor(raw), raw);

            assertEquals(chars, schema.getBodyCharCnt());
            assertNull(schema.getExclRsn(), chars + "자는 적재 대상이어야 함");
            assertFalse(schema.isExcluded());
        }
    }

    /**
     * 실측한 39쪽 전자문서 이용안내(8,355자)는 기본 임계에서 걸러져야 한다.
     * 쪽수로 짐작한 값이 아니라 이 실측치가 기본값의 근거다.
     */
    @Test
    void excludesTheMeasuredGuideDocument() {
        RawDocument raw = document("전자문서_이용안내.pdf", 8_355);

        SchemaResult schema = new LoadPolicy(LoadPolicy.DEFAULT_MAX_BODY_CHARS)
                .apply(schemaFor(raw), raw);

        assertEquals(8_355, schema.getBodyCharCnt());
        assertTrue(schema.isExcluded());
        assertEquals("본문 8,355자 (임계 5,000자 초과)", schema.getExclRsn());
    }

    /** 경계값은 포함(초과일 때만 제외) — 임계치 정의를 애매하게 두면 재현이 안 된다. */
    @Test
    void thresholdIsInclusive() {
        LoadPolicy policy = new LoadPolicy(100);

        assertNull(policy.skipReasonFor(100));
        assertTrue(policy.skipReasonFor(101).contains("101"));
    }

    /** 0 이하는 제한 없음 — 임계 판정을 끄는 문서화된 방법이다. */
    @Test
    void zeroOrNegativeMeansUnlimited() {
        assertNull(new LoadPolicy(0).skipReasonFor(1_000_000));
        assertNull(new LoadPolicy(-1).skipReasonFor(1_000_000));
        assertNull(LoadPolicy.unlimited().skipReasonFor(1_000_000));
    }

    /** 설정을 읽어 규칙을 만든다(값이 없으면 기본값). */
    @Test
    void readsThresholdFromProperties() {
        assertEquals(LoadPolicy.DEFAULT_MAX_BODY_CHARS,
                LoadPolicy.fromProperties(AppProperties.load()).maxBodyChars());
    }

    /** 병합 셀은 grid에 반복돼 분량이 부풀지만, 천 단위 임계에서 판정이 뒤집히지는 않는다. */
    @Test
    void mergedCellsInflateCountButNotEnoughToFlipTheVerdict() {
        RawDocument raw = new RawDocument("병합.hml", "hml", false);
        raw.getContent().add(new RawTable(1, 3,
                List.of(new RawCell(0, 0, 1, 3, "관리청")),
                List.of(List.of("관리청", "관리청", "관리청"))));

        assertEquals(9, DocumentSize.bodyCharCnt(raw));
        assertNull(new LoadPolicy(LoadPolicy.DEFAULT_MAX_BODY_CHARS)
                .skipReasonFor(DocumentSize.bodyCharCnt(raw)));
    }

    /** 지정한 글자 수만큼 본문을 가진 문서를 만든다. */
    private static RawDocument document(String name, int chars) {
        RawDocument raw = new RawDocument(name, "pdf", false);
        raw.getContent().add(new RawParagraph("가".repeat(chars)));
        return raw;
    }

    /** 매퍼가 만들었을 법한 빈 스키마. */
    private static SchemaResult schemaFor(RawDocument raw) {
        return new SchemaResult(raw.getFileNm(), raw.getFileExtn(), raw.isScanYn(), "pdfbox");
    }
}
