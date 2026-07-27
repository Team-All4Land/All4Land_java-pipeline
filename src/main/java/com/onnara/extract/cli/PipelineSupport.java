package com.onnara.extract.cli;

import com.onnara.extract.common.Json;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.detect.DetectorRegistry;
import com.onnara.extract.engine.Extractor;
import com.onnara.extract.engine.ExtractorRegistry;
import com.onnara.extract.scan.ImageOcrEnricher;
import com.onnara.extract.scan.ScanOcrRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    /** OCR가 본문에 기여했을 때 엔진 식별자에 덧붙이는 꼬리표(§6 documents.engine). */
    private static final String OCR_ENGINE_SUFFIX = "+paddleocr-vl";

    /** 인스턴스화 방지 — pipeline/extract가 공유하는 정적 헬퍼 모음. */
    private PipelineSupport() {
    }

    /** 결과 raw 문서 + 실제 사용된 엔진 식별자(§6 documents.engine). */
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
     * <p>스캔 PDF만 원본을 통째로 OCR 서브프로세스에 넘긴다(페이지 렌더링이 필요).
     * 그 밖의 문서는 스캔 판정과 무관하게 <b>네이티브 추출 + 임베디드 이미지 OCR</b>을
     * 함께 돌린다 — 판별이 어느 쪽으로 틀려도 잃는 게 없도록.
     *
     * <ul>
     *   <li>사진이 대부분인 네이티브 문서를 스캔본으로 오판해도 문단·표가 그대로 남는다.</li>
     *   <li>네이티브로 판정해도 사진·위치도 속 정보가 OCR로 raw JSON에 들어온다.</li>
     * </ul>
     *
     * @param outputDir 스키마/원시 JSON이 저장될 기준 폴더 — 이미지는 {@code outputDir/images}에 저장되고
     *                  {@code RawImage.path}는 저장된 이미지의 절대경로로 기록된다(ref_files.file_path 적재용).
     * @param imageOcr  임베디드 이미지 OCR 실행기. null이면(스크립트 없음/옵션으로 끔) 이미지 OCR을 건너뛴다
     */
    static ExtractResult extractOne(Path file, Extractor forcedExtractor, Path outputDir,
                                    boolean saveImages, ScanOcrRunner scanRunner,
                                    ImageOcrEnricher imageOcr) throws IOException {
        String ext = extensionOf(file);
        String sourceFile = file.getFileName().toString();
        boolean scanned = forcedExtractor == null && DetectorRegistry.isScanned(file);

        if (scanned && "pdf".equals(ext)) {
            RawDocument raw = scanRunner.parsePdf(file, sourceFile);
            return new ExtractResult(raw, "paddleocr-vl");
        }

        Extractor extractor = forcedExtractor != null ? forcedExtractor : ExtractorRegistry.forExtension(ext);
        RawDocument raw = extractor.extractRaw(file);
        raw.setScanned(scanned);
        return new ExtractResult(raw,
                extractor.engineName() + ocrImages(file, raw, ext, extractor, outputDir, saveImages, scanned, imageOcr));
    }

    /**
     * 임베디드 이미지를 디스크에 쓰고 OCR해 raw에 병합한다. 엔진 식별자에 덧붙일 꼬리표를 반환한다
     * (기여가 없으면 빈 문자열).
     *
     * <p>스캔 판정본은 이미지가 사실상 유일한 본문 출처라 OCR 실패를 그대로 올려 파일을
     * [실패]로 격리하고, 네이티브 판정본은 이미 뽑아 둔 문단·표가 있으므로 실패해도
     * 경고만 남기고 계속한다.
     */
    private static String ocrImages(Path file, RawDocument raw, String ext, Extractor extractor,
                                    Path outputDir, boolean saveImages, boolean scanned,
                                    ImageOcrEnricher imageOcr) throws IOException {
        if (raw.getImages().isEmpty()) {
            return "";
        }
        boolean wantOcr = imageOcr != null;
        if (!saveImages && !wantOcr) {
            return "";
        }

        // --no-images여도 OCR에는 실제 파일이 필요하므로 임시 폴더에 뽑고 나중에 지운다
        Path tempDir = saveImages ? null : Files.createTempDirectory("extract-ocr-images-");
        try {
            List<Path> saved = extractor.saveImages(file, saveImages ? outputDir.resolve("images") : tempDir);
            if (saveImages) {
                bindImagePaths(raw, saved);
            }
            if (!wantOcr || saved.isEmpty()) {
                return "";
            }
            try {
                return imageOcr.enrich(raw, saved, ext) > 0 ? OCR_ENGINE_SUFFIX : "";
            } catch (IOException | RuntimeException e) {
                if (scanned) {
                    throw e;
                }
                System.out.println("[경고] " + file + ": 이미지 OCR을 건너뜁니다 — " + e.getMessage());
                return "";
            }
        } finally {
            deleteTempDir(tempDir);
        }
    }

    /** 임시 이미지 폴더를 통째로 지운다(정리 실패는 무시 — OS가 정리한다). */
    private static void deleteTempDir(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 개별 파일 정리 실패는 무시
                }
            });
        } catch (IOException ignored) {
            // 임시 폴더 정리 실패는 무시
        }
    }

    /** saveImages 반환 순서 == raw.images 순서 계약을 이용해 절대 경로를 채운다. */
    static void bindImagePaths(RawDocument raw, List<Path> saved) {
        List<RawImage> images = raw.getImages();
        for (int i = 0; i < images.size() && i < saved.size(); i++) {
            images.get(i).setPath(absolutePath(saved.get(i)));
        }
    }

    /** 저장된 이미지 파일의 절대 경로 문자열(ref_files.file_path 적재용, 구분자는 '/'). */
    private static String absolutePath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
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
