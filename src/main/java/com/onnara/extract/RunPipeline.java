package com.onnara.extract;

import com.onnara.extract.model.ExtractedDocument;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 사용법:
 *   java -jar hwp-extract-pipeline.jar <파일 또는 폴더> [--db output.db] [--images-dir images] [--ocr] [--ocr-lang kor+eng]
 *
 * 폴더를 지정하면 하위의 모든 .hwp/.hwpx/.hml 파일을 재귀적으로 순회하며 처리한다.
 */
public class RunPipeline {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("사용법: java -jar hwp-extract-pipeline.jar <파일 또는 폴더> [--db output.db] [--images-dir images] [--ocr] [--ocr-lang kor+eng]");
            System.exit(1);
        }

        String target = args[0];
        String dbPath = "hwpx_extract.db";
        String imagesDir = "extracted_images";
        boolean runOcr = false;
        String ocrLang = "kor+eng";

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--db":
                    dbPath = args[++i];
                    break;
                case "--images-dir":
                    imagesDir = args[++i];
                    break;
                case "--ocr":
                    runOcr = true;
                    break;
                case "--ocr-lang":
                    ocrLang = args[++i];
                    break;
                default:
                    System.out.println("알 수 없는 옵션: " + args[i]);
            }
        }

        List<File> files = collectTargetFiles(target);
        if (files.isEmpty()) {
            System.out.println("처리할 .hwp/.hwpx/.hml 파일이 없습니다.");
            System.exit(1);
        }

        DbLoader dbLoader = new DbLoader(dbPath);

        int totalTables = 0, totalImages = 0, succeeded = 0, failed = 0;
        for (File file : files) {
            try {
                ExtractedDocument doc = parseAny(file.getPath());
                long documentId = dbLoader.saveDocument(doc, imagesDir, runOcr, ocrLang);
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

    private static ExtractedDocument parseAny(String path) throws Exception {
        String lower = path.toLowerCase();
        if (lower.endsWith(".hml")) {
            return new HwpmlExtractor().parse(path);
        } else if (lower.endsWith(".hwpx")) {
            return new HwpxExtractor().parse(path);
        } else if (lower.endsWith(".hwp")) {
            return new HwpExtractor().parse(path);
        }
        throw new IllegalArgumentException("지원하지 않는 확장자입니다 (hwp, hwpx, hml만 지원): " + path);
    }

    private static List<File> collectTargetFiles(String target) throws Exception {
        File targetFile = new File(target);
        List<File> result = new ArrayList<>();

        if (targetFile.isFile()) {
            result.add(targetFile);
            return result;
        }

        if (targetFile.isDirectory()) {
            try (Stream<Path> walk = Files.walk(targetFile.toPath())) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.toString().toLowerCase();
                            return n.endsWith(".hwp") || n.endsWith(".hwpx") || n.endsWith(".hml");
                        })
                        .sorted(Comparator.naturalOrder())
                        .forEach(p -> result.add(p.toFile()));
            }
            return result;
        }

        throw new IllegalArgumentException("'" + target + "' 경로를 찾을 수 없습니다.");
    }
}
