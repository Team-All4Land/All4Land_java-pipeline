package com.onnara.extract.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SourceFolder} 폴더명 → 기관·게시판 파싱 단위 테스트. */
class SourceFolderTest {

    /**
     * 실제 크롤 산출물 폴더명 전부를 훑는다.
     *
     * <p>여기 나열한 이름은 손으로 지은 예가 아니라 크롤러가 실제로 만든 폴더들이다.
     * 파서를 고칠 일이 생기면 이 표가 회귀를 잡는다.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // 폴더명                         | 번호 | 하위 | 기관명             | 종류 | 게시판
            "1_인천지방해양수산청           | 1    | 0    | 인천지방해양수산청 | MOF  | POST",
            "10_동해지방해양수산청          | 10   | 0    | 동해지방해양수산청 | MOF  | POST",
            "12_1_목포시청                  | 12   | 1    | 목포시청           | LOCL | POST",
            "12_2_목포시청_지난자료         | 12   | 2    | 목포시청           | LOCL | CLSD",
            "13_2_여수시청_완료된 고시공고  | 13   | 2    | 여수시청           | LOCL | CLSD",
            "17_2_보성군청_이전 공고        | 17   | 2    | 보성군청           | LOCL | CLSD",
            "18_1_장흥군청_고시공고         | 18   | 1    | 장흥군청           | LOCL | POST",
            "20_1_해남군청_고시공고(새울)   | 20   | 1    | 해남군청           | LOCL | POST",
            "29_1_수영구                    | 29   | 1    | 수영구             | LOCL | POST",
            "29_3_수영구_09.01.11 이전 공고 | 29   | 3    | 수영구             | LOCL | CLSD",
            "30_1_기장군                    | 30   | 1    | 기장군             | LOCL | POST",
            "51_1_울진군청_게시중           | 51   | 1    | 울진군청           | LOCL | POST",
            "51_2_울진군청_완료된           | 51   | 2    | 울진군청           | LOCL | CLSD",
            "54_2_통영시청_지난고시         | 54   | 2    | 통영시청           | LOCL | CLSD",
            "56_2_거제시청_이전고시         | 56   | 2    | 거제시청           | LOCL | CLSD",
            "65_2_양양군청_이전공고         | 65   | 2    | 양양군청           | LOCL | CLSD",
            "69_제주특별자치도청            | 69   | 0    | 제주특별자치도청   | LOCL | POST",
            "70_새만금개발청                | 70   | 0    | 새만금개발청       | CNTL | POST",
            "71_농림축산식품부              | 71   | 0    | 농림축산식품부     | CNTL | POST",
    })
    void parsesRealCrawlerFolderNames(String folderName, int folderNo, int subNo,
                                      String name, String kind, String board) {
        SourceFolder.Parsed parsed = SourceFolder.parse(folderName).orElseThrow();
        assertEquals(folderNo, parsed.folderNo(), folderName);
        assertEquals(subNo, parsed.subNo(), folderName);
        assertEquals(name, parsed.agncyNm(), folderName);
        assertEquals(kind, parsed.kndCd(), folderName);
        assertEquals(board, parsed.boardCd(), folderName);
    }

    /**
     * 언더스코어가 든 기관명은 잘리지 않아야 한다.
     *
     * <p>경상남도 고성군과 강원도 고성군은 이름이 같아 광역 접두로만 갈린다. 마지막 조각을
     * 무조건 게시판 꼬리표로 보면 둘 다 "고성군청"이 돼 한 기관으로 뭉쳐 버린다.
     */
    @Test
    void keepsUnderscoresThatBelongToTheAgencyName() {
        assertEquals("경상남도 고성군청",
                SourceFolder.parse("57_경상남도_고성군청").orElseThrow().agncyNm());
        assertEquals("강원도 고성군청",
                SourceFolder.parse("64_강원도_고성군청").orElseThrow().agncyNm());
    }

    /**
     * 전자관보는 기관명이 아니라 수집 경로다 — 이름에서 빼고 {@code kind_code}로 옮긴다.
     *
     * <p>그래야 자체 게시판(71_농림축산식품부)과 전자관보 경유분이 같은 이름을 갖고도
     * 질의로 갈린다.
     */
    @Test
    void movesGazettePrefixIntoKindCode() {
        SourceFolder.Parsed parsed = SourceFolder.parse("72_1_전자관보_농림축산식품부").orElseThrow();
        assertEquals(72, parsed.folderNo());
        assertEquals(1, parsed.subNo());
        assertEquals("농림축산식품부", parsed.agncyNm());
        assertEquals("GZT", parsed.kndCd());
        assertEquals("POST", parsed.boardCd());

        assertEquals("새만금개발청",
                SourceFolder.parse("72_2_전자관보_새만금개발청").orElseThrow().agncyNm());
    }

    /**
     * 규약에 맞지 않는 폴더는 기관으로 승격시키지 않는다 —
     * 손으로 만든 작업 폴더까지 넣으면 DB에 유령 기관이 생긴다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"samples", "임시", "72_", "_72_목포시청", "12345678_목포시청"})
    void rejectsFoldersOutsideTheConvention(String folderName) {
        assertTrue(SourceFolder.parse(folderName).isEmpty(), folderName);
    }

    /** null과 빈 문자열에도 예외 없이 empty를 돌려줘야 한다(배치가 폴더 하나에 멈추면 안 된다). */
    @Test
    void toleratesMissingNames() {
        assertEquals(Optional.empty(), SourceFolder.parse(null));
        assertEquals(Optional.empty(), SourceFolder.parse(""));
    }

    /**
     * 전자관보 게시물의 비고에서 발령 기관을 읽는다.
     *
     * <p>비고 원문은 08.07 산출물에 실제로 실려 온 두 문장이다. 전자관보 159건이 전부
     * 이 둘 중 하나이며(농림축산식품부 82건 · 새만금개발청 77건), 비고가 빈 행은 없다.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "웹사이트에서 제목이 \"농림축산식품부고시\"로 시작함 | 농림축산식품부",
            "웹사이트에서 제목이 \"새만금개발청고시\"로 시작함   | 새만금개발청",
    })
    void readsGazetteAgencyFromRemark(String remark, String expected) {
        assertEquals(expected, SourceFolder.gazetteAgencyOf(remark).orElseThrow());
    }

    /**
     * 읽어낸 이름 앞에 {@code 전자관보_}를 붙이면 검증요약의 기관명이 되고,
     * 그때부터는 다른 기관과 똑같은 경로를 탄다 — 전자관보 전용 분기가 여기서 끝난다.
     */
    @Test
    void resolvedGazetteAgencyFeedsTheNormalParser() {
        String agency = SourceFolder.gazetteAgencyOf(
                "웹사이트에서 제목이 \"새만금개발청고시\"로 시작함").orElseThrow();

        SourceFolder.Parsed parsed = SourceFolder.parse("72_2_전자관보_" + agency).orElseThrow();
        assertEquals("새만금개발청", parsed.agncyNm());
        assertEquals(SourceFolder.INSTT_KND_GAZETTE, parsed.kndCd(),
                "전자관보는 기관 종류가 아니라 수집 경로다");
        assertEquals(72, parsed.folderNo());
        assertEquals(2, parsed.subNo());
    }

    /** 수집결과 시트의 지자체명이 전자관보인 행만 비고를 본다. */
    @Test
    void gazetteRowsAreTheOnlyOnesNeedingTheRemark() {
        assertTrue(SourceFolder.isGazette("전자관보"));
        assertTrue(SourceFolder.isGazette("  전자관보  "));
        assertFalse(SourceFolder.isGazette("전자관보_농림축산식품부"),
                "그 이름은 검증요약 쪽이고, 수집결과 지자체명은 전자관보 한 값뿐이다");
        assertFalse(SourceFolder.isGazette("인천지방해양수산청"));
        assertFalse(SourceFolder.isGazette(null));
    }

    /**
     * 근거가 없으면 기관을 붙이지 않는다.
     *
     * <p>엉뚱한 기관에 밀어 넣으면 기관별 집계가 조용히 오염되는데, 그 오염은 전자관보
     * 두 기관 사이에서만 일어나 전체 건수로는 드러나지 않는다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "첨부파일이 존재하지 않음.",
            "웹사이트에서 제목이 농림축산식품부고시로 시작함",   // 따옴표가 없다
            "웹사이트에서 제목이 \"\"로 시작함",                 // 따옴표 안이 비었다
            "",
    })
    void refusesToGuessWhenTheRemarkDoesNotSayIt(String remark) {
        assertTrue(SourceFolder.gazetteAgencyOf(remark).isEmpty(), remark);
    }

    /** 비고가 없는 행에서도 배치가 멈추면 안 된다. */
    @Test
    void toleratesMissingRemark() {
        assertEquals(Optional.empty(), SourceFolder.gazetteAgencyOf(null));
    }
}
