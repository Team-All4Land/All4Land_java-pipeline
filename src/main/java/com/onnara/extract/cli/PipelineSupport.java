package com.onnara.extract.cli;

import com.onnara.extract.common.Json;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.detect.DetectorRegistry;
import com.onnara.extract.engine.Extractor;
import com.onnara.extract.engine.ExtractorRegistry;
import com.onnara.extract.ocr.TesseractOcr;
import com.onnara.extract.scan.ScanOcrClient;

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
 * {@link #bindImagePaths}는 그 계약을 전제로 인덱스 매칭한다.
 */
final class PipelineSupport {

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
     *                  {@code RawImage.path}는 outputDir 기준 상대경로로 기록된다(§4 예시와 동일한 형태).
     */
    static ExtractResult extractOne(Path file, Extractor forcedExtractor, Path outputDir,
                                    boolean saveImages, boolean ocr, TesseractOcr tesseractOcr,
                                    ScanOcrClient scanClient) throws IOException {
        String ext = extensionOf(file);
        String sourceFile = file.getFileName().toString();
        Path imagesDir = outputDir.resolve("images");

        if (forcedExtractor == null && DetectorRegistry.isScanned(file)) {
            return extractScanned(file, ext, sourceFile, outputDir, imagesDir, saveImages, scanClient);
        }

        Extractor extractor = forcedExtractor != null ? forcedExtractor : ExtractorRegistry.forExtension(ext);
        RawDocument raw = extractor.extractRaw(file);
        if (saveImages && !raw.getImages().isEmpty()) {
            List<Path> saved = extractor.saveImages(file, imagesDir);
            bindImagePaths(raw, saved, outputDir);
            if (ocr && tesseractOcr != null) {
                applyOcr(raw, saved, tesseractOcr);
            }
        }
        return new ExtractResult(raw, extractor.engineName());
    }

    /**
     * 스캔본 경로: PDF는 원본 파일을, HWP/HWPX/HML은 Java가 추출한 임베디드 이미지들을
     * OCR 서비스로 전송한다(§1, §7). 서비스 응답의 images 메타는 우리가 로컬에 저장한
     * 파일 목록으로 재구성한다 — 응답이 요청 이미지와 다른 목록/순서를 돌려줄 수 있어서다.
     */
    private static ExtractResult extractScanned(Path file, String ext, String sourceFile,
                                                Path outputDir, Path imagesDir, boolean saveImages,
                                                ScanOcrClient scanClient) throws IOException {
        if ("pdf".equals(ext)) {
            RawDocument raw = scanClient.parsePdf(file, sourceFile);
            return new ExtractResult(raw, "ocr-service");
        }

        Extractor nativeExtractor = ExtractorRegistry.forExtension(ext);
        Path targetDir = saveImages ? imagesDir : Files.createTempDirectory("extract-scan-");
        List<Path> saved = nativeExtractor.saveImages(file, targetDir);

        RawDocument raw = scanClient.parseImages(saved, sourceFile, ext);
        raw.setImages(new ArrayList<>());
        for (Path p : saved) {
            RawImage image = new RawImage(p.getFileName().toString(), Files.size(p));
            if (saveImages) {
                image.setPath(outputDir.relativize(p).toString().replace('\\', '/'));
            }
            raw.getImages().add(image);
        }
        return new ExtractResult(raw, "ocr-service");
    }

    /** saveImages 반환 순서 == raw.images 순서 계약을 이용해 상대 경로를 채운다. */
    static void bindImagePaths(RawDocument raw, List<Path> saved, Path outBase) {
        List<RawImage> images = raw.getImages();
        for (int i = 0; i < images.size() && i < saved.size(); i++) {
            Path rel = outBase.relativize(saved.get(i));
            images.get(i).setPath(rel.toString().replace('\\', '/'));
        }
    }

    private static void applyOcr(RawDocument raw, List<Path> saved, TesseractOcr ocr) {
        List<RawImage> images = raw.getImages();
        for (int i = 0; i < images.size() && i < saved.size(); i++) {
            String text = ocr.recognize(saved.get(i));
            if (text != null) {
                images.get(i).setOcrText(text);
            }
        }
    }

    static void writeJson(Object obj, Path dest) throws IOException {
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        Json.PRETTY.writeValue(dest.toFile(), obj);
    }

    static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
