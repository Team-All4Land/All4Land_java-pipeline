package com.onnara.extract.cli;

import com.onnara.extract.common.AppProperties;
import com.onnara.extract.common.Mapper;
import com.onnara.extract.common.model.SchemaResult;
import com.onnara.extract.common.table.InterpretedTable;
import com.onnara.extract.common.table.TableDoc;
import com.onnara.extract.common.table.TableInterpreter;
import com.onnara.extract.db.DataSourceFactory;
import com.onnara.extract.db.DbLoader;
import com.onnara.extract.db.DbSchema;
import com.onnara.extract.db.LoadStats;
import com.onnara.extract.detect.DetectorRegistry;
import com.onnara.extract.scan.ImageOcrEnricher;
import com.onnara.extract.scan.ScanOcrConfig;
import com.onnara.extract.scan.ScanOcrRunner;
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
 * <p>흐름(§8): input/ 재귀 스캔 → 스캔본이 있으면 OCR 스크립트 존재 확인 →
 * 파일별 detect → extract → map → schema.json 저장 → (--no-db가 아니면) DB 적재.
 *
 * <p>DB·OCR 실행 설정은 application.properties에서만 읽는다(로컬 실행 전제).
 */
@Command(name = "pipeline", description = "배치: 판별→추출→매핑→적재 일괄")
public class PipelineCommand implements Callable<Integer> {

    /** 재귀 스캔할 입력 폴더(기본 input). */
    @Option(names = {"-i", "--input"}, defaultValue = "input", description = "입력 폴더")
    Path input;

    /** 스키마/원시 JSON과 images/가 저장될 출력 폴더(기본 out). */
    @Option(names = {"-o", "--output"}, defaultValue = "out", description = "출력 폴더")
    Path output;

    /** true면 스키마 JSON과 함께 원시 결과(*.raw.json)도 저장. */
    @Option(names = "--raw", description = "원시 결과(*.raw.json)도 함께 저장")
    boolean raw;

    /** true면 표 해석 중간 결과(*.tables.json)도 저장 — 매핑 진단용. */
    @Option(names = "--tables", description = "표 해석 중간 결과(*.tables.json)도 함께 저장")
    boolean tables;

    /** true면 이미지 파일 저장을 생략. */
    @Option(names = "--no-images", description = "이미지 저장 생략")
    boolean noImages;

    /** true면 문서에 삽입된 이미지의 OCR을 생략한다(추론 시간 단축용). */
    @Option(names = "--no-image-ocr",
            description = "문서에 삽입된 사진·위치도의 OCR 생략 (스캔본 처리에는 영향 없음)")
    boolean noImageOcr;

    /** true면 DB 적재 단계를 건너뛴다(DB 없는 스모크런용). */
    @Option(names = "--no-db", description = "DB 적재 생략 (DB 없는 스모크런용)")
    boolean noDb;

    /**
     * 입력 폴더를 재귀 수집해 파일별로 판별→추출→매핑→저장하고, --no-db가 아니면
     * 마지막에 DB로 적재한다. 스캔본이 있으면 시작 전에 OCR 스크립트 존재를 확인한다.
     * 파일 단위 실패는 [실패] 로그로 격리하며, 하나라도 실패하면 종료 코드 1.
     */
    @Override
    public Integer call() throws Exception {
        AppProperties props = AppProperties.load();
        List<Path> files = PipelineSupport.collectInputs(List.of(input));
        if (files.isEmpty()) {
            System.out.println("입력 폴더에 처리할 파일이 없습니다: " + input);
            return 0;
        }

        ScanOcrConfig ocrConfig = ScanOcrConfig.fromProperties(props);
        if (noImageOcr) {
            ocrConfig = ocrConfig.withoutImageOcr();
        }
        ScanOcrRunner scanRunner = new ScanOcrRunner(ocrConfig);
        boolean ocrReady = checkOcrRunnerIfNeeded(files, scanRunner, ocrConfig);
        ImageOcrEnricher imageOcr = imageOcrIfUsable(scanRunner, ocrConfig);

        List<SchemaResult> schemas = new ArrayList<>();
        int ok = 0;
        int failed = 0;
        for (Path file : files) {
            try {
                if (!ocrReady && DetectorRegistry.isScanned(file)) {
                    throw new IOException("OCR 실행 스크립트가 없어 스캔본을 처리할 수 없습니다");
                }
                PipelineSupport.ExtractResult result = PipelineSupport.extractOne(
                        file, null, output, !noImages, scanRunner, imageOcr);
                // 표 해석은 한 번만 하고 중간 산출물 저장과 매핑이 함께 쓴다
                List<InterpretedTable> interpreted = TableInterpreter.interpret(
                        TableInterpreter.tablesOf(result.raw()));
                SchemaResult schema = Mapper.mapToSchema(result.raw(), result.engine(), interpreted);

                String stem = PipelineSupport.stem(file);
                if (raw) {
                    PipelineSupport.writeJson(result.raw(), output.resolve(stem + ".raw.json"));
                }
                if (tables) {
                    PipelineSupport.writeJson(
                            TableDoc.of(result.raw().getSourceFile(), result.raw().getFileType(),
                                    result.raw().isScanned(), result.engine(), interpreted),
                            output.resolve(stem + ".tables.json"));
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

    /**
     * 이미지 OCR을 쓸 수 있으면 실행기를, 아니면 null을 돌려준다(배치 전체에 한 번만 판단·경고).
     *
     * <p>이미지 OCR은 네이티브 결과에 얹는 보강이라 스크립트가 없어도 배치를 세우지 않는다.
     * 다만 사진이 대부분인 문서는 본문 대부분이 빠진 채로 적재되므로 그 사실은 알린다.
     */
    private ImageOcrEnricher imageOcrIfUsable(ScanOcrRunner scanRunner, ScanOcrConfig ocrConfig) {
        if (!ocrConfig.imageOcrEnabled()) {
            return null;
        }
        ImageOcrEnricher enricher = new ImageOcrEnricher(scanRunner, ocrConfig);
        if (!enricher.isUsable()) {
            System.out.println("[경고] OCR 실행 스크립트를 찾을 수 없어(" + ocrConfig.script()
                    + ") 문서에 삽입된 사진·위치도의 내용은 추출되지 않습니다.");
            return null;
        }
        return enricher;
    }

    /** 스캔본이 1건 이상일 때만 OCR 스크립트 존재를 확인한다(§8) — 스캔본이 없으면 스크립트 없이도 배치가 동작한다. */
    private boolean checkOcrRunnerIfNeeded(List<Path> files, ScanOcrRunner scanRunner, ScanOcrConfig ocrConfig) {
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
        boolean ready = scanRunner.isAvailable();
        if (!ready) {
            System.out.println("[경고] OCR 실행 스크립트를 찾을 수 없습니다(" + ocrConfig.script()
                    + "). 스캔본 파일은 [실패] 처리됩니다.");
        }
        return ready;
    }

    /** 커넥션 풀을 열어 스키마를 마이그레이션한 뒤 수집된 스키마 결과들을 documents/ref_files에 적재한다. */
    private void loadToDatabase(AppProperties props, List<SchemaResult> schemas) throws Exception {
        try (HikariDataSource dataSource = DataSourceFactory.create(props)) {
            DbSchema.migrate(dataSource);
            try (DbLoader loader = new DbLoader(dataSource)) {
                LoadStats stats = loader.loadAll(schemas);
                System.out.printf("DB 적재: %d개 파일, documents %d행, ref_files %d행%n",
                        stats.filesOk(), stats.documentsInserted(), stats.refFilesInserted());
            }
        }
    }
}
