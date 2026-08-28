package com.onnara.extract.cli;

import com.onnara.extract.common.Errors;
import com.onnara.extract.common.LoadStep;
import com.onnara.extract.common.Json;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.detect.DetectorRegistry;
import com.onnara.extract.detect.DocFormat;
import com.onnara.extract.detect.FailureClassifier;
import com.onnara.extract.detect.FailureKind;
import com.onnara.extract.engine.Extractor;
import com.onnara.extract.engine.ExtractorRegistry;
import com.onnara.extract.scan.ScanOcrRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * pipeline/extract 서브커맨드가 공유하는 파일 수집·추출·저장 로직.
 *
 * <p>이미지 경로 계약: {@code Extractor.saveImages}가 반환하는 목록은
 * {@code extractRaw}의 {@code images}와 순서·이름이 동일해야 하며,
 * {@link #bindImagePaths}는 그 계약을 전제로 인덱스 매칭해 절대경로를 채운다.
 */
final class PipelineSupport {

    /** 인스턴스화 방지 — pipeline/extract가 공유하는 정적 헬퍼 모음. */
    private PipelineSupport() {
    }

    /** 결과 raw 문서 + 실제 사용된 엔진 식별자(§6 OS_ATCH_FILE_DTL.EXTC_ENGN_NM). */
    record ExtractResult(RawDocument raw, String engine) {
    }

    /** 대상(파일 또는 디렉터리) 목록에서 지원 확장자 파일을 재귀 수집해 정렬 반환. */
    static List<Path> collectInputs(List<Path> targets) throws IOException {
        Set<String> exts = ExtractorRegistry.supportedExtensions();
        List<Path> files = new ArrayList<>();
        for (Path target : targets) {
            if (Files.isDirectory(target)) {
                try (Stream<Path> walk = Files.walk(target)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> exts.contains(extensionOf(p)))
                            .forEach(files::add);
                }
            } else if (Files.isRegularFile(target)) {
                files.add(target);
            } else {
                System.out.println("[실패] " + target + ": 파일이 존재하지 않습니다");
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    /**
     * 파일 하나를 추출한다. {@code forcedExtractor}가 있으면 스캔 판별을 건너뛰고
     * 항상 해당 엔진으로 네이티브 추출한다({@code extract --engine} 옵션).
     *
     * <p>OCR은 스캔 판정 파일만 탄다. 네이티브 문서의 임베디드 이미지는 파일로 저장하고
     * {@code images} 메타만 남긴다 — 사진 속 글자를 본문에 섞지 않는다.
     *
     * @param outputDir 스키마/원시 JSON이 저장될 기준 폴더 — 이미지는 {@code outputDir/images}에 저장되고
     *                  {@code RawImage.path}는 저장된 이미지의 절대경로로 기록된다(OS_ATCH_IMG_DTL.IMG_FILE_PATH 적재용).
     */
    static ExtractResult extractOne(Path file, Extractor forcedExtractor, Path outputDir,
                                    boolean saveImages, ScanOcrRunner scanRunner) throws IOException {
        boolean scanned = forcedExtractor == null && DetectorRegistry.isScanned(file);
        return extractOne(file, forcedExtractor, outputDir, saveImages, scanRunner, scanned);
    }

    /**
     * 스캔 판별 결과를 이미 알고 있을 때 쓰는 진입점 — 배치가 판별을 한 번만 돌리기 위한 것이다.
     *
     * @param scanned 이 파일이 스캔본인지({@code forcedExtractor}가 있으면 무시된다)
     */
    static ExtractResult extractOne(Path file, Extractor forcedExtractor, Path outputDir,
                                    boolean saveImages, ScanOcrRunner scanRunner,
                                    boolean scanned) throws IOException {
        // 라우팅 근거는 확장자가 아니라 파일 내용이다 — .hwp로 저장된 HWPX를 살리기 위해서다
        String format = DocFormat.routingKey(file);
        String atchFileNm = file.getFileName().toString();
        Path imagesDir = outputDir.resolve("images");

        if (forcedExtractor == null && scanned) {
            ExtractResult result = extractScanned(
                    file, format, atchFileNm, imagesDir, saveImages, scanRunner);
            return new ExtractResult(tagFormats(result.raw(), file, format), result.engine());
        }

        Extractor extractor = forcedExtractor != null
                ? forcedExtractor : ExtractorRegistry.forExtension(format);
        RawDocument raw = extractor.extractRaw(file);
        if (saveImages && !raw.getImages().isEmpty()) {
            bindImagePaths(raw, extractor.saveImages(file, imagesDir));
        }
        return new ExtractResult(tagFormats(raw, file, format), extractor.engineName());
    }

    /**
     * 형식 두 축을 확정한다 — {@code FILE_EXTN_NM}은 <b>파일명 확장자</b>,
     * {@code ACTL_FILE_EXTN_NM}은 <b>내용으로 판정한 실제 형식</b>.
     *
     * <p>엔진은 자기가 아는 형식 이름을 {@code fileExtnNm}에 박아 넣는다(예: {@code OwpmlExtractor}는
     * 항상 {@code "hwpx"}). 그대로 두면 {@code .hwp}로 저장된 HWPX가 {@code FILE_EXTN_NM=hwpx}로
     * 적재돼 기존 집계 쿼리와 값이 어긋난다. 두 축을 여기서 한 번에 정리해, 엔진은 형식 판정
     * 정책을 몰라도 되게 한다.
     */
    static RawDocument tagFormats(RawDocument raw, Path file, String format) {
        raw.setAtchFilePath(absolutePath(file));
        raw.setFileExtnNm(extensionOf(file));
        raw.setActlFileExtnNm(format);
        return raw;
    }

    /**
     * 스캔본 경로: PDF는 원본 파일을, HWP/HWPX/HML은 Java가 추출한 임베디드 이미지들을
     * PaddleOCR-VL 서브프로세스로 넘긴다(§1, §7). 결과의 images 메타는 우리가 로컬에 저장한
     * 파일 목록으로 재구성한다 — 결과가 입력 이미지와 다른 목록/순서를 돌려줄 수 있어서다.
     */
    private static ExtractResult extractScanned(Path file, String format, String atchFileNm,
                                                Path imagesDir, boolean saveImages,
                                                ScanOcrRunner scanRunner) throws IOException {
        if ("pdf".equals(format)) {
            RawDocument raw = scanRunner.parsePdf(file, atchFileNm);
            return new ExtractResult(raw, "paddleocr-vl");
        }

        Extractor nativeExtractor = ExtractorRegistry.forExtension(format);
        // --no-images일 때 쓰는 임시 폴더는 이 파일 처리가 끝나면 지운다. 남겨 두면 배치 한 번에
        // 스캔본 수만큼 폴더가 /tmp에 쌓여 디스크를 먹는다(OCR에 넘긴 뒤에는 쓸 일이 없다).
        Path tempDir = saveImages ? null : Files.createTempDirectory("extract-scan-");
        Path targetDir = saveImages ? imagesDir : tempDir;
        try {
            List<Path> saved = nativeExtractor.saveImages(file, targetDir);

            RawDocument raw = scanRunner.parseImages(saved, atchFileNm, format);
            raw.setImages(new ArrayList<>());
            for (Path p : saved) {
                RawImage image = new RawImage(p.getFileName().toString(), Files.size(p));
                if (saveImages) {
                    image.setPath(absolutePath(p));
                }
                raw.getImages().add(image);
            }
            return new ExtractResult(raw, "paddleocr-vl");
        } finally {
            deleteQuietly(tempDir);
        }
    }

    /** 임시 폴더와 그 안의 파일을 지운다. 실패해도 배치를 막지 않는다(정리 실패는 치명적이지 않다). */
    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 다음 파일 정리로 넘어간다
                }
            });
        } catch (IOException ignored) {
            // 정리 실패는 로그로 남길 가치가 없다 — OS가 /tmp를 회수한다
        }
    }

    /** saveImages 반환 순서 == raw.images 순서 계약을 이용해 절대 경로를 채운다. */
    static void bindImagePaths(RawDocument raw, List<Path> saved) {
        List<RawImage> images = raw.getImages();
        for (int i = 0; i < images.size() && i < saved.size(); i++) {
            images.get(i).setPath(absolutePath(saved.get(i)));
        }
    }

    /** 저장된 이미지 파일의 절대 경로 문자열(OS_ATCH_IMG_DTL.IMG_FILE_PATH 적재용, 구분자는 '/'). */
    private static String absolutePath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /**
     * 실패 한 건을 로그로 남기고, {@code --failures} 산출물에 넣을 행을 만든다.
     *
     * <p>메시지는 {@link Errors#describe}로 원인 체인까지 적는다 — 맨 바깥 예외의 메시지만 찍으면
     * "읽지 못했다"는 사실만 남고 왜인지가 사라져, 수백 건의 실패를 사후에 분류할 수 없다.
     *
     * @param step       실패한 단계 — 한 try에 뭉쳐 있으면 어디서 깨졌는지 알 수 없어
     *                   재현이 어렵다. 콘솔에는 한글, 산출물에는 표준코드가 실린다
     * @param stacktrace true면 스택 트레이스도 stderr로 남긴다
     */
    static Map<String, Object> reportFailure(Path file, LoadStep step, Throwable t, boolean stacktrace) {
        if (stacktrace) {
            t.printStackTrace(System.err);
        }
        FailureKind kind = FailureClassifier.classify(file, t).kind();
        String chain = Errors.describe(t);
        Map<String, Object> row = reportFailure(file, step, chain, kind.name());
        // 원인 체인과 안내문은 쓰임이 다르다 — 체인은 "라이브러리가 무슨 말을 하며 죽었나"이고,
        // 안내문은 "받는 사람이 무엇을 하면 되나"다. 체인만 남기면 This is not paragraph. 같은
        // 문장을 받아 든 담당자가 원인을 처음부터 다시 파야 한다.
        //
        // 갈래의 설명을 쓴다. Result.detail()은 갈래마다 구성이 달라(원인 체인만인 것도 있고
        // 안내문에 체인을 덧댄 것도 있다) 여기서 쓰면 message와 같은 문장이 두 번 실린다.
        row.put("reason", kind.description());
        row.put("exception", t.getClass().getName());
        // 콘솔에도 한 줄 붙인다 — 배치를 돌린 사람이 실제로 보는 것은 여기다.
        // 이미 같은 문장이 체인에 있으면(판별기가 안내문을 그대로 던진 경우) 적지 않는다.
        if (!chain.contains(kind.description())) {
            System.out.println("         └ " + kind.description());
        }
        return row;
    }

    /**
     * 이미 사유가 서술된 실패를 보고한다 — 판별 단계처럼 {@link com.onnara.extract.detect.ScanSurvey}가
     * 갈래와 원인 체인을 이미 갖고 있는 경우다. 그것을 예외로 다시 감싸면 사유가
     * {@code IOException: UncheckedIOException: …}처럼 이중으로 찍혀 읽기 나빠진다.
     */
    static Map<String, Object> reportFailure(Path file, LoadStep step, String message, String kind) {
        System.out.println("[실패] " + file + ": (" + step.label() + ") " + message);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("file", file.toString());
        row.put("ext", extensionOf(file));
        row.put("stage", step.code());
        row.put("kind", kind);
        row.put("message", message);
        return row;
    }

    /** {@code --failures} 산출물을 저장한다 — 실패 목록을 손으로 추리지 않아도 되게. */
    static void writeFailures(List<Map<String, Object>> failures, int total, Path dest)
            throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("total", total);
        report.put("failed", failures.size());
        report.put("failures", failures);
        writeJson(report, dest);
        System.out.println("실패 " + failures.size() + "건을 " + dest + "에 저장했습니다");
    }

    /** 객체를 들여쓰기 JSON으로 저장한다(상위 폴더는 자동 생성). */
    static void writeJson(Object obj, Path dest) throws IOException {
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        Json.PRETTY.writeValue(dest.toFile(), obj);
    }

    /** 소문자 확장자(점 제외). 확장자가 없으면 빈 문자열. */
    static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 확장자를 제외한 파일명(출력 파일명 어간으로 사용). */
    static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
