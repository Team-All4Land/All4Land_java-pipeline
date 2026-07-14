package com.onnara.extract.cli;

import com.onnara.extract.common.AppProperties;
import com.onnara.extract.common.Mapper;
import com.onnara.extract.common.model.SchemaResult;
import com.onnara.extract.db.DataSourceFactory;
import com.onnara.extract.db.DbLoader;
import com.onnara.extract.db.DbSchema;
import com.onnara.extract.db.LoadStats;
import com.onnara.extract.detect.DetectorRegistry;
import com.onnara.extract.ocr.TesseractOcr;
import com.onnara.extract.scan.ScanOcrClient;
import com.onnara.extract.scan.ScanOcrConfig;
import com.zaxxer.hikari.HikariDataSource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code pipeline}: 판별 → 추출 → 매핑 → 적재 일괄 실행 (§8).
 *
 * <p>흐름(§8): input/ 재귀 스캔 → 스캔본이 있으면 OCR 서비스 health 확인 →
 * 파일별 detect → extract → map → schema.json 저장 → (--no-db가 아니면) DB 적재.
 */
@Command(name = "pipeline", description = "배치: 판별→추출→매핑→적재 일괄")
public class PipelineCommand implements Callable<Integer> {

    @Option(names = {"-i", "--input"}, defaultValue = "input", description = "입력 폴더")
    Path input;

    @Option(names = {"-o", "--output"}, defaultValue = "out", description = "출력 폴더")
    Path output;

    @Option(names = "--db-url", description = "PostgreSQL JDBC URL 재정의")
    String dbUrl;

    @Option(names = "--db-user", description = "DB 사용자 재정의")
    String dbUser;

    @Option(names = "--db-password", description = "DB 비밀번호 재정의")
    String dbPassword;

    @Option(names = "--ocr-url", description = "OCR 서비스 URL 재정의")
    String ocrUrl;

    @Option(names = "--raw", description = "원시 결과(*.raw.json)도 함께 저장")
    boolean raw;

    @Option(names = "--no-images", description = "이미지 저장 생략")
    boolean noImages;

    @Option(names = "--ocr", description = "임베디드 이미지 Tesseract OCR 수행")
    boolean ocr;

    @Option(names = "--no-db", description = "DB 적재 생략 (DB 없는 스모크런용)")
    boolean noDb;

    @Override
    public Integer call() throws Exception {
        AppProperties props = AppProperties.load();
        List<Path> files = PipelineSupport.collectInputs(List.of(input));
        if (files.isEmpty()) {
            System.out.println("입력 폴더에 처리할 파일이 없습니다: " + input);
            return 0;
        }

        ScanOcrClient scanClient = new ScanOcrClient(ScanOcrConfig.fromProperties(props, ocrUrl));
        boolean ocrHealthy = checkOcrHealthIfNeeded(files, scanClient);
        TesseractOcr tesseractOcr = ocr ? new TesseractOcr() : null;

        List<SchemaResult> schemas = new ArrayList<>();
        int ok = 0;
        int failed = 0;
        for (Path file : files) {
            try {
                if (!ocrHealthy && DetectorRegistry.isScanned(file)) {
                    throw new IOException("OCR 서비스에 연결할 수 없어 스캔본을 처리할 수 없습니다");
                }
                PipelineSupport.ExtractResult result = PipelineSupport.extractOne(
                        file, null, output, !noImages, ocr, tesseractOcr, scanClient);
                SchemaResult schema = Mapper.mapToSchema(result.raw(), result.engine());

                String stem = PipelineSupport.stem(file);
                if (raw) {
                    PipelineSupport.writeJson(result.raw(), output.resolve(stem + ".raw.json"));
                }
                PipelineSupport.writeJson(schema, output.resolve(stem + ".schema.json"));
                schemas.add(schema);
                ok++;
            } catch (Exception e) {
                failed++;
                System.out.println("[실패] " + file + ": " + e.getMessage());
            }
        }

        if (!noDb && !schemas.isEmpty()) {
            loadToDatabase(props, schemas);
        }

        System.out.printf("총 %d개 중 %d개 완료, %d개 실패%n", files.size(), ok, failed);
        return failed == 0 ? 0 : 1;
    }

    /** 스캔본이 1건 이상일 때만 서비스 상태를 확인한다(§8) — 스캔본이 없으면 서비스 없이도 배치가 동작한다. */
    private boolean checkOcrHealthIfNeeded(List<Path> files, ScanOcrClient scanClient) {
        boolean anyScanned = false;
        for (Path file : files) {
            try {
                if (DetectorRegistry.isScanned(file)) {
                    anyScanned = true;
                    break;
                }
            } catch (Exception ignored) {
                // 판별 실패는 추출 단계에서 [실패]로 다시 잡힌다
            }
        }
        if (!anyScanned) {
            return true;
        }
        boolean healthy = scanClient.health();
        if (!healthy) {
            System.out.println("[경고] OCR 서비스에 연결할 수 없습니다. 스캔본 파일은 [실패] 처리됩니다.");
        }
        return healthy;
    }

    private void loadToDatabase(AppProperties props, List<SchemaResult> schemas) throws Exception {
        try (HikariDataSource dataSource = DataSourceFactory.create(props, dbUrl, dbUser, dbPassword)) {
            DbSchema.migrate(dataSource);
            try (DbLoader loader = new DbLoader(dataSource)) {
                LoadStats stats = loader.loadAll(schemas);
                System.out.printf("DB 적재: %d개 파일, documents %d행, ref_files %d행%n",
                        stats.filesOk(), stats.documentsInserted(), stats.refFilesInserted());
            }
        }
    }
}
