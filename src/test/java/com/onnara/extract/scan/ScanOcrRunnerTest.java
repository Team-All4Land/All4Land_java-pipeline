package com.onnara.extract.scan;

import com.onnara.extract.common.model.RawDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 스텁 셸 스크립트를 {@code /bin/sh}로 실행해 서브프로세스 계약을 검증한다
 * (인자 전달, --output JSON 파싱, 종료 코드/타임아웃 실패 처리).
 */
@DisabledOnOs(OS.WINDOWS)
class ScanOcrRunnerTest {

    @TempDir
    Path tempDir;

    private ScanOcrRunner runnerFor(Path script, Duration timeout) {
        return new ScanOcrRunner(new ScanOcrConfig("/bin/sh", script, timeout));
    }

    /** --output 뒤의 경로에 준비된 raw JSON을 쓰고, 전체 인자를 덤프하는 스텁. */
    private Path successScript(Path argsDump, Path rawJsonSource) throws IOException {
        String script = """
                out=
                prev=
                for a in "$@"; do
                  if [ "$prev" = "--output" ]; then out="$a"; fi
                  prev="$a"
                done
                echo "$@" > '%s'
                cat '%s' > "$out"
                """.formatted(argsDump, rawJsonSource);
        return writeScript(script);
    }

    private Path writeScript(String body) throws IOException {
        Path script = Files.createTempFile(tempDir, "stub", ".sh");
        Files.writeString(script, body);
        return script;
    }

    private Path rawJsonFile(String sourceFile, String fileType) throws IOException {
        Path json = tempDir.resolve("prepared-raw.json");
        Files.writeString(json, "{\"source_file\":\"" + sourceFile + "\",\"file_type\":\"" + fileType + "\","
                + "\"is_scanned\":false,\"content\":[],\"images\":[],\"markdown\":\"# ignored\"}");
        return json;
    }

    @Test
    void parseImagesPassesArgsAndParsesOutputJson() throws Exception {
        Path image = tempDir.resolve("img0.png");
        Files.write(image, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        Path argsDump = tempDir.resolve("args.txt");
        Path script = successScript(argsDump, rawJsonFile("scan.hwpx", "hwpx"));

        RawDocument raw = runnerFor(script, Duration.ofSeconds(10))
                .parseImages(List.of(image), "scan.hwpx", "hwpx");

        String args = Files.readString(argsDump);
        assertTrue(args.contains("--source-file scan.hwpx"), "인자 전달: " + args);
        assertTrue(args.contains("--file-type hwpx"), "인자 전달: " + args);
        assertTrue(args.contains("--input-kind images"), "이미지 목록 입력임을 알려야 함: " + args);
        assertTrue(args.contains(image.toAbsolutePath().toString()), "입력 이미지 경로 전달: " + args);
        assertTrue(raw.isScanned(), "결과 JSON의 is_scanned=false여도 true로 강제되어야 함");
        assertEquals("scan.hwpx", raw.getSourceFile());
    }

    @Test
    void parsePdfPassesPdfPath() throws Exception {
        Path pdf = tempDir.resolve("scan.pdf");
        Files.write(pdf, new byte[]{'%', 'P', 'D', 'F'});
        Path argsDump = tempDir.resolve("args.txt");
        Path script = successScript(argsDump, rawJsonFile("scan.pdf", "pdf"));

        RawDocument raw = runnerFor(script, Duration.ofSeconds(10)).parsePdf(pdf, "scan.pdf");

        String args = Files.readString(argsDump);
        assertTrue(args.contains("--file-type pdf"), "인자 전달: " + args);
        assertTrue(args.contains("--input-kind document"), "페이지 렌더링이 필요한 원본임을 알려야 함: " + args);
        assertTrue(args.contains(pdf.toAbsolutePath().toString()), "PDF 경로 전달: " + args);
        assertEquals("scan.pdf", raw.getSourceFile());
    }

    @Test
    void throwsWithLogTailOnNonZeroExit() throws Exception {
        Path script = writeScript("""
                echo "모델 로드 실패: paddle 미설치" >&2
                exit 3
                """);
        ScanOcrRunner runner = runnerFor(script, Duration.ofSeconds(10));

        ScanOcrException e = assertThrows(ScanOcrException.class,
                () -> runner.parseImages(List.of(), "x.hwp", "hwp"));
        assertTrue(e.getMessage().contains("종료 코드 3"), e.getMessage());
        assertTrue(e.getMessage().contains("모델 로드 실패"), "stderr 꼬리 노출: " + e.getMessage());
    }

    @Test
    void throwsWhenOutputJsonMissing() throws Exception {
        Path script = writeScript("exit 0\n");
        ScanOcrRunner runner = runnerFor(script, Duration.ofSeconds(10));

        ScanOcrException e = assertThrows(ScanOcrException.class,
                () -> runner.parseImages(List.of(), "x.hwp", "hwp"));
        assertTrue(e.getMessage().contains("결과 JSON"), e.getMessage());
    }

    @Test
    void killsProcessOnTimeout() throws Exception {
        Path script = writeScript("sleep 10\n");
        ScanOcrRunner runner = runnerFor(script, Duration.ofMillis(300));

        long start = System.nanoTime();
        ScanOcrException e = assertThrows(ScanOcrException.class,
                () -> runner.parseImages(List.of(), "x.hwp", "hwp"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(e.getMessage().contains("제한 시간"), e.getMessage());
        assertTrue(elapsedMs < 5000, "타임아웃 시 즉시 중단해야 함: " + elapsedMs + "ms");
    }

    @Test
    void isAvailableChecksScriptExists() throws Exception {
        assertFalse(runnerFor(tempDir.resolve("없는스크립트.py"), Duration.ofSeconds(1)).isAvailable());
        assertTrue(runnerFor(writeScript("exit 0\n"), Duration.ofSeconds(1)).isAvailable());
    }
}
