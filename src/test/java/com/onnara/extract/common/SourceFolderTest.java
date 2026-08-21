package com.onnara.extract.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            // 폴더명                            | 번호 | 하위 | 기관명             | 종류    | 게시판
            "1_인천지방해양수산청                | 1  | 0 | 인천지방해양수산청 | mof     | 게시중",
            "10_동해지방해양수산청               | 10 | 0 | 동해지방해양수산청 | mof     | 게시중",
            "12_1_목포시청                       | 12 | 1 | 목포시청           | local   | 게시중",
            "12_2_목포시청_지난자료              | 12 | 2 | 목포시청           | local   | 게시완료",
            "13_2_여수시청_완료된 고시공고       | 13 | 2 | 여수시청           | local   | 게시완료",
            "17_2_보성군청_이전 공고             | 17 | 2 | 보성군청           | local   | 게시완료",
            "18_1_장흥군청_고시공고              | 18 | 1 | 장흥군청           | local   | 게시중",
            "20_1_해남군청_고시공고(새울)        | 20 | 1 | 해남군청           | local   | 게시중",
            "29_1_수영구                         | 29 | 1 | 수영구             | local   | 게시중",
            "29_3_수영구_09.01.11 이전 공고      | 29 | 3 | 수영구             | local   | 게시완료",
            "30_1_기장군                         | 30 | 1 | 기장군             | local   | 게시중",
            "51_1_울진군청_게시중                | 51 | 1 | 울진군청           | local   | 게시중",
            "51_2_울진군청_완료된                | 51 | 2 | 울진군청           | local   | 게시완료",
            "54_2_통영시청_지난고시              | 54 | 2 | 통영시청           | local   | 게시완료",
            "56_2_거제시청_이전고시              | 56 | 2 | 거제시청           | local   | 게시완료",
            "65_2_양양군청_이전공고              | 65 | 2 | 양양군청           | local   | 게시완료",
            "69_제주특별자치도청                 | 69 | 0 | 제주특별자치도청   | local   | 게시중",
            "70_새만금개발청                     | 70 | 0 | 새만금개발청       | central | 게시중",
            "71_농림축산식품부                   | 71 | 0 | 농림축산식품부     | central | 게시중",
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
        assertEquals("gazette", parsed.kndCd());
        assertEquals("게시중", parsed.boardCd());

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
}
