package com.onnara.extract.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NoticeTypes} 제목 → 공고종류 분류 단위 테스트. */
class NoticeTypesTest {

    /**
     * 실제 고시문 제목이 맞는 종류로 가야 한다.
     *
     * <p>여기 적은 제목은 지어낸 것이 아니라 전수 입력에서 빈도순으로 뽑은 것들이다.
     */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "공유수면 점용ㆍ사용 변경허가 고시          | OCUPY_CHG_PRMSN",
            "공유수면 점용·사용 허가 고시              | OCUPY_PRMSN",
            "공유수면 점용ㆍ사용 변경승인 고시          | OCUPY_CHG_APRV",
            "공유수면 점․사용 변경(기간연장)허가 고시   | OCUPY_EXTN_CHG_PRMSN",
            "공유수면 점․사용 변경(연장)허가 고시       | OCUPY_EXTN_CHG_PRMSN",
            "공유수면 점용·사용 승인 고시              | OCUPY_APRV",
            "공유수면 점용·사용 협의 고시              | OCUPY_CNSLT",
            "공유수면 점용·사용허가 취소 고시           | OCUPY_PRMSN_CNCL",
            "공유수면 점용·사용 실시계획 승인 고시      | IMPL_APRV",
            "공유수면 점용·사용 실시계획 신고 수리 고시 | IMPL_RPT_ACPT",
            "공유수면 점용·사용 실시계획 변경승인 고시  | IMPL_CHG_APRV",
            "공유수면 점용·사용 실시계획 준공검사확인증 발급 고시 | ETC_CMPL_ACPT",
            "공유수면 매립면허 고시                    | RECLM_LCNS",
            "공유수면 점용료·사용료 감면기준 고시       | FEE_REDCT",
            "방치선박 제거공고                         | ETC_ABND_RMV",
            "공유수면 점용ㆍ사용허가의 면제대상 시설 고시 | ETC_PRMSN_EXMPT",
    })
    void classifiesRealNoticeTitles(String title, String expected) {
        assertEquals(expected, NoticeTypes.classify(title).orElseThrow(), title);
    }

    /**
     * 같은 말을 네 가지로 적는다 — 가운뎃점 변형과 약어가 같은 종류로 모여야 한다.
     *
     * <p>{@code ㆍ}(U+318D), {@code ․}(U+2024), {@code ·}(U+00B7)가 서식마다 다르고
     * {@code 점용·사용}을 {@code 점․사용}으로 줄여 적기도 한다. 접지 않으면 규칙을 네 벌 써야 한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "공유수면 점용ㆍ사용 변경허가 고시",
            "공유수면 점용․사용 변경허가 고시",
            "공유수면 점용·사용 변경허가 고시",
            "공유수면 점․사용 변경허가 고시",
            "공유수면 점․사용변경허가",
            "공유수면점용사용변경허가고시",
    })
    void foldsSeparatorAndAbbreviationVariants(String title) {
        assertEquals("OCUPY_CHG_PRMSN", NoticeTypes.classify(title).orElseThrow(), title);
    }

    /**
     * 구체적인 규칙이 일반 규칙을 이겨야 한다.
     *
     * <p>"기간연장 변경허가"에는 "변경+허가"도 "허가"도 들어 있다. priority가 없으면 파일에
     * 적힌 순서에 좌우돼, 종류를 하나 보태는 것만으로 분류가 통째로 뒤집힌다.
     */
    @Test
    void prefersTheMoreSpecificRule() {
        assertEquals("OCUPY_EXTN_CHG_PRMSN",
                NoticeTypes.classify("공유수면 점용·사용 변경(기간연장)허가 고시").orElseThrow());
        assertEquals("OCUPY_CHG_PRMSN",
                NoticeTypes.classify("공유수면 점용·사용 변경허가 고시").orElseThrow());
        assertEquals("OCUPY_PRMSN",
                NoticeTypes.classify("공유수면 점용·사용 허가 고시").orElseThrow());

        assertEquals("IMPL_RPT_ACPT",
                NoticeTypes.classify("실시계획 신고수리 고시").orElseThrow());
        assertEquals("IMPL_RPT",
                NoticeTypes.classify("실시계획 신고 고시").orElseThrow());
    }

    /**
     * 어느 종류에도 자리가 없는 문서는 비워 둬야 한다.
     *
     * <p>설계서·현장사진·평가서·통보서가 첨부에 실제로 섞여 있다. 가장 비슷한 종류에 밀어 넣으면
     * 종류별 집계가 조용히 오염되고, 오염됐다는 사실을 나중에 알 방법이 없다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "면허 변경에 관한 통보서", "사업수행능력평가서 평가기준 및 작성안내서",
            "부두 내 보안가로등 보수공사 설계서", "비응항 서측호안 방파제 사이 취수관",
    })
    void leavesOffDomainTitlesUnclassified(String title) {
        assertTrue(NoticeTypes.classify(title).isEmpty(), title);
    }

    /**
     * 입찰공고는 ETC_BID_NOTI로 간다 — 자리가 <b>없는</b> 것과 자리를 <b>안 만든</b> 것은 다르다.
     *
     * <p>워크북 55종에 항목이 없어 제목이 멀쩡한 첨부 11건이 통째로 NULL이었다. 공유수면 처분이
     * 아니라 조달 공고이지만, 같은 게시판에서 온 이상 "무엇인지 모르는 문서"로 두는 것보다
     * 이름을 붙여 집계에서 걸러 낼 수 있게 하는 편이 낫다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "소액수의 입찰 공고", "물품구매입찰공고(소액수의)", "공사 입찰 공고",
            "폐기물처리용역 입찰공고(소액수의)", "공사 입찰 변경공고",
    })
    void classifiesBidNotices(String title) {
        assertEquals("ETC_BID_NOTI", NoticeTypes.classify(title).orElseThrow(), title);
    }

    /**
     * 입찰공고 규칙이 공유수면 처분을 가로채면 안 된다.
     *
     * <p>{@code priority}를 10으로 낮게 잡은 이유가 이것이다 — 두 성격이 겹친 제목은
     * 점용·사용 쪽이 이겨야 한다.
     */
    @Test
    void bidRuleDoesNotOutrankDispositionRules() {
        assertEquals("OCUPY_CHG_PRMSN", NoticeTypes.classify(
                "공유수면 점용·사용 변경허가 고시").orElseThrow());
        assertEquals("OCUPY_GEN", NoticeTypes.classify(
                "공유수면 점용·사용 관련 입찰 안내").orElseThrow());
    }

    /**
     * 근거법령 이름에 든 "매립"에 속으면 안 된다.
     *
     * <p>「공유수면 관리 및 <b>매립</b>에 관한 법률」이 거의 모든 고시문에 인용된다. 제목
     * 추출이 본문 첫 문장을 집어 온 경우 이 이름이 제목 자리에 오는데, 그대로 두면 점용·사용
     * 허가 문서가 매립으로 분류된다. 실입력에서 매립이 든 제목 135건 중 124건이 이것이었다.
     */
    @Test
    void isNotFooledByTheLawNameContainingReclamation() {
        assertEquals("OCUPY_PRMSN", NoticeTypes.classify(
                "「공유수면 관리 및 매립에 관한 법률」제8조에 따라 다음과 같이"
                        + " 공유수면 점용ㆍ사용 허가를 하였기에 고시합니다").orElseThrow());

        // 진짜 매립 문서는 그대로 매립으로 가야 한다
        assertEquals("RECLM_LCNS", NoticeTypes.classify("공유수면 매립면허 고시").orElseThrow());
        assertEquals("RECLM_CMPL", NoticeTypes.classify("공유수면 매립공사 준공 고시").orElseThrow());
    }

    /** 제목이 없으면 분류하지 않는다 — 첨부 173건이 제목 없이 온다. */
    @Test
    void classifiesNothingWithoutATitle() {
        assertTrue(NoticeTypes.classify(null).isEmpty());
        assertTrue(NoticeTypes.classify("").isEmpty());
        assertTrue(NoticeTypes.classify("   ").isEmpty());
    }

    /**
     * 규칙이 가리키는 코드는 전부 레지스트리에 있어야 한다.
     *
     * <p>{@code TB_ATCH_FILE.NOTI_KND_CD}가 {@code TB_NOTI_KND}를 참조하는 FK라,
     * 레지스트리에 없는 코드를 내면 첫 적재가 FK 위반으로 죽는다. 그때는 이미 배치 중반이다.
     */
    @Test
    void everyRuleTargetsARegisteredType() {
        Set<String> codes = new HashSet<>();
        for (NoticeTypes.Spec spec : NoticeTypes.all()) {
            codes.add(spec.notiKndCd());
        }
        for (NoticeTypes.Spec spec : NoticeTypes.all()) {
            for (var words : spec.keywords()) {
                assertTrue(!words.isEmpty(), spec.notiKndCd() + ": 빈 규칙은 모든 제목을 삼킨다");
            }
            assertTrue(codes.contains(spec.notiKndCd()));
        }
        assertEquals(56, codes.size(), "공고종류는 56종이다");
    }
}
