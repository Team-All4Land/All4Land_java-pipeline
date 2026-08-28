package com.onnara.extract.cli;

import com.onnara.extract.common.model.RawDocument;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PipelineSupport#tagFormats} 단위 테스트 — 첨부 행의 형식 두 축과 원본 경로가
 * 여기서 정해진다({@code FILE_EXTN_NM}, {@code ACTL_FILE_EXTN_NM}, {@code ATCH_FILE_PATH}).
 *
 * <p>파일 내용은 읽지 않고 이름과 경로만 쓰므로 실제 파일이 필요 없다. 형식 판별 자체는
 * {@link com.onnara.extract.detect.DocFormatTest}가 덮으므로 여기서는 <b>판별 결과를
 * 어느 컬럼에 싣는가</b>만 본다.
 */
class PipelineSupportTest {

    /** 엔진이 채워 놓은 값을 흉내 낸 raw에 형식 두 축을 다시 박는다. */
    private static RawDocument tagged(String engineExtn, String fileName, String format) {
        RawDocument raw = new RawDocument();
        raw.setFileExtnNm(engineExtn);
        return PipelineSupport.tagFormats(raw, Path.of(fileName), format);
    }

    /**
     * <b>이 함수의 존재 이유</b> — {@code .hwp}로 저장된 HWPX는 파일명 확장자와 실제 형식이
     * 갈려야 한다.
     *
     * <p>{@code OwpmlExtractor}는 자기가 아는 이름("hwpx")을 raw에 박아 넣는다. 그대로 두면
     * {@code FILE_EXTN_NM=hwpx}로 적재돼, 확장자별로 세던 기존 집계와 값이 어긋난다.
     */
    @Test
    void keepsTheNameExtensionWhenTheContentSaysAnotherFormat() {
        RawDocument raw = tagged("hwpx", "개명 hwpx - 공유수면 점사용 변경허가.hwp", "hwpx");

        assertEquals("hwp", raw.getFileExtnNm(), "파일명이 말하는 확장자");
        assertEquals("hwpx", raw.getActlFileExtnNm(), "내용이 말하는 실제 형식");
    }

    /** 확장자와 내용이 일치하는 평범한 파일은 두 축이 같은 값이다. */
    @Test
    void leavesBothAxesEqualWhenTheExtensionMatchesTheContent() {
        RawDocument raw = tagged("hwpx", "일반 고시문.hwpx", "hwpx");

        assertEquals("hwpx", raw.getFileExtnNm());
        assertEquals("hwpx", raw.getActlFileExtnNm());
    }

    /**
     * 확장자는 소문자로 눕힌다 — {@code .HWP}와 {@code .hwp}가 다른 값으로 적재되면
     * 확장자별 집계가 둘로 쪼개진다.
     */
    @Test
    void lowercasesTheNameExtension() {
        assertEquals("hwp", tagged("hwp", "대문자.HWP", "hwp").getFileExtnNm());
    }

    /** 점이 없는 파일명은 확장자가 빈 문자열이다 — null이 아니어야 컬럼 길이 검사에 걸리지 않는다. */
    @Test
    void leavesTheExtensionEmptyWhenTheNameHasNoDot() {
        assertEquals("", tagged("hwp", "확장자없음", "hwp").getFileExtnNm());
    }

    /**
     * 경로는 절대경로로, 구분자는 {@code /}로 적재한다.
     *
     * <p>윈도우에서 돌린 배치가 역슬래시 경로를 남기면 같은 파일이 OS마다 다른 문자열로
     * 들어가, 적재된 행에서 원본을 되짚는 유일한 끈이 끊긴다.
     */
    @Test
    void writesAnAbsolutePathWithForwardSlashes() {
        String path = tagged("hwp", "상대경로 고시문.hwp", "hwp").getAtchFilePath();

        assertTrue(Path.of(path).isAbsolute(), "절대경로여야 한다: " + path);
        assertFalse(path.contains("\\"), "구분자를 /로 눕혀야 한다: " + path);
        assertTrue(path.endsWith("/상대경로 고시문.hwp"), path);
    }
}
