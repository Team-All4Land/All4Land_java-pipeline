package com.onnara.extract;

import com.onnara.extract.DocumentExtractor;
import com.onnara.extract.model.ExtractedDocument;
import com.onnara.extract.model.ExtractedImage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 사용법:
 *   java -jar hwp-extract-pipeline.jar <파일 또는 폴더> [--db output.db] [--images-dir images]
 *
 * 폴더를 지정하면 등록된 확장자(EXTRACTORS의 키)에 해당하는 파일을 재귀적으로 순회하며 처리한다.
 *
 * 다른 포맷(PDF 등) 통합 방법
 * ---------------------------
 * EXTRACTORS 맵에 확장자 -> DocumentExtractor 구현체만 추가하면 된다.
 * 예: EXTRACTORS.put("pdf", new PdfExtractor());
 * RunPipeline 자체나 DbLoader는 손댈 필요 없음 (포맷 중립적으로 설계됨).
 *
 * OCR 통합 방법
 * -------------
 * 이 프로젝트는 OCR 구현체를 포함하지 않는다. OcrProvider 인터페이스를 구현한
 * 객체를 아래 ocrProvider 필드에 넣어주면, 이미지마다 자동으로 호출되어
 * 결과가 images_extracted.ocr_text에 저장된다. 예:
 *   ocrProvider = imageData -> myTeamOcrService.recognize(imageData);
 * 안 쓰면 null로 두면 되고, 그러면 ocr_text는 전부 NULL로 저장된다.
 */
public class RunPipeline {

    /** 확장자(소문자, 점 없이) -> 파서. 다른 포맷 추가 시 여기에만 등록하면 됨. */
    private static final Map<String, DocumentExtractor> EXTRACTORS = new LinkedHashMap<>();
    static {
        EXTRACTORS.put("hwp", new HwpExtractor());
        EXTRACTORS.put("hwpx", new HwpxExtractor());
        EXTRACTORS.put("hml", new HwpmlExtractor());
        // 예: EXTRACTORS.put("pdf", new PdfExtractor());
    }

    /** OCR을 쓰려면 다른 담당자의 구현체를 여기에 연결. 기본은 미사용(null). */
    private static OcrProvider ocrProvider = null;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("사용법: java -jar hwp-extract-pipeline.jar <파일 또는 폴더> [--db output.db] [--images-dir images]");
            System.exit(1);
        }

        String target = args[0];
        String dbPath = "hwpx_extract.db";
        String imagesDir = "extracted_images";

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--db":
                    dbPath = args[++i];
                    break;
                case "--images-dir":
                    imagesDir = args[++i];
                    break;
                default:
                    System.out.println("알 수 없는 옵션: " + args[i]);
            }
        }

        List<File> files = collectTargetFiles(target);
        if (files.isEmpty()) {
            System.out.println("처리할 파일이 없습니다 (지원 확장자: " + EXTRACTORS.keySet() + ")");
            System.exit(1);
        }

        DbLoader dbLoader = new DbLoader(dbPath);

        int totalTables = 0, totalImages = 0, succeeded = 0, failed = 0;
        for (File file : files) {
            try {
                DocumentExtractor extractor = EXTRACTORS.get(extension(file.getName()));
                ExtractedDocument doc = extractor.parse(file.getPath());

                Map<String, String> ocrTextByImageId = runOcrIfConfigured(doc);

                long documentId = dbLoader.saveDocument(doc, imagesDir, ocrTextByImageId);
                totalTables += doc.getTables().size();
                totalImages += doc.getImages().size();
                succeeded++;
                System.out.printf("[완료] %s -> document_id=%d, 표 %d개, 이미지 %d개, 문단 %d개%n",
                        file.getName(), documentId, doc.getTables().size(),
                        doc.getImages().size(), doc.getParagraphs().size());
            } catch (Exception e) {
                System.out.printf("[실패] %s: %s%n", file.getPath(), e.getMessage());
                failed++;
            }
        }

        dbLoader.close();

        System.out.printf("%n총 %d개 중 %d개 처리 완료%s. 표 %d개, 이미지 %d개 -> %s%n",
                files.size(), succeeded, failed > 0 ? (", " + failed + "개 실패") : "",
                totalTables, totalImages, dbPath);
    }

    private static Map<String, String> runOcrIfConfigured(ExtractedDocument doc) {
        if (ocrProvider == null) return null;
        Map<String, String> result = new HashMap<>();
        for (ExtractedImage image : doc.getImages()) {
            String text = ocrProvider.recognize(image.getData());
            if (text != null && !text.isEmpty()) {
                result.put(image.getImageId(), text);
            }
        }
        return result;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }

    private static List<File> collectTargetFiles(String target) throws Exception {
        File targetFile = new File(target);
        List<File> result = new ArrayList<>();

        if (targetFile.isFile()) {
            if (EXTRACTORS.containsKey(extension(targetFile.getName()))) {
                result.add(targetFile);
            }
            return result;
        }

        if (targetFile.isDirectory()) {
            try (Stream<Path> walk = Files.walk(targetFile.toPath())) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> EXTRACTORS.containsKey(extension(p.getFileName().toString())))
                        .sorted(Comparator.naturalOrder())
                        .forEach(p -> result.add(p.toFile()));
            }
            return result;
        }

        throw new IllegalArgumentException("'" + target + "' 경로를 찾을 수 없습니다.");
    }
}