package com.onnara.extract.cli;

import com.onnara.extract.common.ImageProbe;
import com.onnara.extract.common.Json;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.detect.DetectorRegistry;
import com.onnara.extract.engine.Extractor;
import com.onnara.extract.engine.ExtractorRegistry;
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
     * @param outputDir 스키마/원시 JSON이 저장될 기준 폴더 — 이미지는 {@code outputDir/images}에 저장되고
     *                  {@code RawImage.path}는 저장된 이미지의 절대경로로 기록된다(ref_files.file_path 적재용).
     */
    static ExtractResult extractOne(Path file, Extractor forcedExtractor, Path outputDir,
                                    boolean saveImages, ScanOcrRunner scanRunner) throws IOException {
        String ext = extensionOf(file);
        String sourceFile = file.getFileName().toString();
        Path imagesDir = outputDir.resolve("images");

        if (forcedExtractor == null && DetectorRegistry.isScanned(file)) {
            return extractScanned(file, ext, sourceFile, imagesDir, saveImages, scanRunner);
        }

        Extractor extractor = forcedExtractor != null ? forcedExtractor : ExtractorRegistry.forExtension(ext);
        RawDocument raw = extractor.extractRaw(file);
        if (!raw.getImages().isEmpty()) {
            ocrEmbeddedImages(file, raw, ext, sourceFile, extractor, imagesDir, saveImages, scanRunner);
        }
        return new ExtractResult(raw, extractor.engineName());
    }

    /**
     * 네이티브 문서에 삽입된 이미지를 OCR해 본문 뒤에 이어 붙인다.
     *
     * <p>붙임 현장사진·위치도처럼 본문이 사진 안에 들어 있는 고시가 흔한데, 그 내용을 뽑는
     * 통로는 스캔 판정 경로뿐이었다. 판별이 정확해질수록 이 문서들은 네이티브로 가고, 그때마다
     * 사진 속 본문이 통째로 사라졌다 — 판별 정확도와 추출량이 서로 깎아먹는 구조였다.
     * 네이티브 경로에도 같은 통로를 두어 그 결합을 끊는다.
     *
     * <p>이미지가 몇 번째 문단에 붙어 있었는지는 hwplib 등에서 복원할 수 없다. 위치를 지어내는
     * 대신 본문 뒤에 이어 붙인다.
     */
    private static void ocrEmbeddedImages(Path file, RawDocument raw, String ext, String sourceFile,
                                          Extractor extractor, Path imagesDir, boolean saveImages,
                                          ScanOcrRunner scanRunner) throws IOException {
        boolean ocr = scanRunner.config().imageOcrEnabled() && scanRunner.isAvailable();
        if (!saveImages && !ocr) {
            return;
        }

        // --no-images여도 OCR에는 실제 파일이 필요하므로 임시 폴더에 뽑고 나중에 지운다
        Path tempDir = saveImages ? null : Files.createTempDirectory("extract-ocr-images-");
        try {
            List<Path> saved = extractor.saveImages(file, saveImages ? imagesDir : tempDir);
            if (saveImages) {
                bindImagePaths(raw, saved);
            }
            List<Path> targets = ocr ? ocrTargets(saved, scanRunner.config().imageMinDimension()) : List.of();
            if (targets.isEmpty()) {
                return;
            }
            try {
                raw.getContent().addAll(scanRunner.parseImages(targets, sourceFile, ext).getContent());
            } catch (IOException | RuntimeException e) {
                // 네이티브 문단·표는 이미 뽑혀 있다 — 사진을 못 읽었다고 파일을 통째로 버리지 않는다
                System.out.println("[경고] " + file + ": 이미지 OCR을 건너뜁니다 — " + e.getMessage());
            }
        } finally {
            deleteTempDir(tempDir);
        }
    }

    /**
     * OCR을 돌릴 이미지만 고른다 — 긴 변이 {@code minDimension}px 이상인 것.
     *
     * <p>도장·관인·로고·서명은 읽어도 본문 정보가 없다. 스캔 판정본만 OCR을 타던 시절과 달리
     * 이제 이미지가 있는 모든 네이티브 문서가 대상이라, 이 필터가 없으면 도장 하나 때문에
     * VLM이 도는 문서가 배치 전체로 번진다.
     *
     * <p>크기를 못 재는 이미지는 후보에 남긴다 — 판단 불가를 '작다'로 취급하면 읽을 수 있었을
     * 본문을 조용히 버리게 된다.
     */
    private static List<Path> ocrTargets(List<Path> images, int minDimension) {
        List<Path> targets = new ArrayList<>();
        for (Path image : images) {
            int max = ImageProbe.maxDimension(image);
            if (max < 0 || max >= minDimension) {
                targets.add(image);
            }
        }
        return targets;
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

    /**
     * 스캔본 경로: PDF는 원본 파일을, HWP/HWPX/HML은 Java가 추출한 임베디드 이미지들을
     * PaddleOCR-VL 서브프로세스로 넘긴다(§1, §7). 결과의 images 메타는 우리가 로컬에 저장한
     * 파일 목록으로 재구성한다 — 결과가 입력 이미지와 다른 목록/순서를 돌려줄 수 있어서다.
     */
    private static ExtractResult extractScanned(Path file, String ext, String sourceFile,
                                                Path imagesDir, boolean saveImages,
                                                ScanOcrRunner scanRunner) throws IOException {
        if ("pdf".equals(ext)) {
            RawDocument raw = scanRunner.parsePdf(file, sourceFile);
            return new ExtractResult(raw, "paddleocr-vl");
        }

        Extractor nativeExtractor = ExtractorRegistry.forExtension(ext);
        Path targetDir = saveImages ? imagesDir : Files.createTempDirectory("extract-scan-");
        List<Path> saved = nativeExtractor.saveImages(file, targetDir);

        RawDocument raw = scanRunner.parseImages(saved, sourceFile, ext);
        raw.setImages(new ArrayList<>());
        for (Path p : saved) {
            RawImage image = new RawImage(p.getFileName().toString(), Files.size(p));
            if (saveImages) {
                image.setPath(absolutePath(p));
            }
            raw.getImages().add(image);
        }
        return new ExtractResult(raw, "paddleocr-vl");
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
