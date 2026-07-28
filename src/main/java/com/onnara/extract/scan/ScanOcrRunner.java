package com.onnara.extract.scan;

import com.onnara.extract.common.Json;
import com.onnara.extract.common.model.RawDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * PaddleOCR-VL CLI 실행기 — 스캔본을 서브프로세스로 처리한다 (§7 계약).
 *
 * <p>호출 규약:
 * <pre>
 * &lt;command&gt; &lt;script&gt; --source-file &lt;원본파일명&gt; --file-type &lt;pdf|hwp|hwpx|hml&gt;
 *                    --output &lt;raw.json 경로&gt; &lt;입력 파일...&gt;
 * </pre>
 * 종료 코드 0이면 {@code --output} 경로에 §4 raw JSON이 쓰여 있어야 하고,
 * stdout/stderr는 로그로만 취급한다(임시 파일로 리다이렉트, 실패 시 꼬리만 노출).
 *
 * <p>역할 분담은 HTTP 시절과 동일: 임베디드 이미지 추출은 Java Extractor가 담당하고,
 * 이 실행기는 "이미지/PDF → 구조화 텍스트" 추론만 맡긴다. 결과의 {@code markdown} 등
 * 계약 외 필드는 {@link Json#MAPPER}가 무시한다.
 */
public final class ScanOcrRunner {

    /** 실패 시 사용자에게 보여줄 서브프로세스 로그 꼬리 길이. */
    private static final int LOG_TAIL_CHARS = 2000;

    /** 실행 커맨드·스크립트 경로·타임아웃 설정. */
    private final ScanOcrConfig config;

    /** 실행 설정을 주입해 생성한다. */
    public ScanOcrRunner(ScanOcrConfig config) {
        this.config = config;
    }

    /** 실행 스크립트가 존재하는지 확인 — pipeline이 스캔본이 있을 때 시작 전에 검사한다. */
    public boolean isAvailable() {
        return Files.isRegularFile(config.script());
    }

    /** 스캔 PDF 원본을 넘긴다(스크립트가 페이지 렌더링 후 VLM 추론). */
    public RawDocument parsePdf(Path pdf, String sourceFile) throws IOException {
        return run(sourceFile, "pdf", List.of(pdf));
    }

    /** 스캔 HWP/HWPX/HML에서 추출한 임베디드 이미지들을 넘긴다. */
    public RawDocument parseImages(List<Path> images, String sourceFile, String fileType) throws IOException {
        return run(sourceFile, fileType, images);
    }

    private RawDocument run(String sourceFile, String fileType, List<Path> inputs) throws IOException {
        Path workDir = Files.createTempDirectory("extract-ocr-");
        Path outputJson = workDir.resolve("raw.json");
        Path log = workDir.resolve("ocr.log");
        try {
            List<String> command = new ArrayList<>();
            command.add(config.command());
            command.add(config.script().toString());
            command.add("--source-file");
            command.add(sourceFile);
            command.add("--file-type");
            command.add(fileType);
            command.add("--output");
            command.add(outputJson.toString());
            for (Path input : inputs) {
                command.add(input.toAbsolutePath().toString());
            }

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            try {
                if (!process.waitFor(config.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    killTree(process);
                    throw new ScanOcrException("OCR 프로세스가 제한 시간(" + config.timeout().toSeconds()
                            + "초)을 초과했습니다: " + sourceFile);
                }
            } catch (InterruptedException e) {
                killTree(process);
                Thread.currentThread().interrupt();
                throw new IOException("OCR 프로세스 대기 중단됨", e);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new ScanOcrException("OCR 프로세스 실패(종료 코드 " + exitCode + "): " + tail(log));
            }
            if (!Files.isRegularFile(outputJson)) {
                throw new ScanOcrException("OCR 프로세스가 결과 JSON을 쓰지 않았습니다: " + tail(log));
            }
            RawDocument raw = Json.MAPPER.readValue(outputJson.toFile(), RawDocument.class);
            raw.setScanned(true);
            return raw;
        } finally {
            deleteQuietly(outputJson);
            deleteQuietly(log);
            deleteQuietly(workDir);
        }
    }

    /**
     * 서브프로세스를 자손까지 통째로 강제 종료한다.
     *
     * <p>{@code destroyForcibly()}는 직계 프로세스 하나만 죽인다 — PaddleOCR가 띄운
     * 추론 워커가 고아로 남아 VLM 모델 메모리(수 GB)를 물고 살아남는 것을 막으려면
     * 부모를 죽이기 전에 자손 목록을 잡아 함께 정리해야 한다.
     */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** 로그 파일 꼬리 — 실패 원인 표시용. Paddle 로그가 길어 전체는 싣지 않는다. */
    private static String tail(Path log) {
        try {
            String text = Files.readString(log, StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                return "(로그 없음)";
            }
            return text.length() <= LOG_TAIL_CHARS ? text : "…" + text.substring(text.length() - LOG_TAIL_CHARS);
        } catch (IOException e) {
            return "(로그 읽기 실패: " + e.getMessage() + ")";
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 임시 파일 정리 실패는 무시 — OS가 정리한다
        }
    }
}
