package com.onnara.extract.cli;

import com.onnara.extract.common.AppProperties;
import com.onnara.extract.common.LoadPolicy;
import com.onnara.extract.common.Mapper;
import com.onnara.extract.common.model.SchemaResult;
import com.onnara.extract.common.table.InterpretedTable;
import com.onnara.extract.common.table.TableDoc;
import com.onnara.extract.common.table.TableInterpreter;
import com.onnara.extract.db.DataSourceFactory;
import com.onnara.extract.db.DbLoader;
import com.onnara.extract.db.DbSchema;
import com.onnara.extract.db.LoadStats;
import com.onnara.extract.detect.ScanSurvey;
import com.onnara.extract.scan.ScanOcrConfig;
import com.onnara.extract.scan.ScanOcrRunner;
import com.zaxxer.hikari.HikariDataSource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /** true면 DB 적재 단계를 건너뛴다(DB 없는 스모크런용). */
    @Option(names = "--no-db", description = "DB 적재 생략 (DB 없는 스모크런용)")
    boolean noDb;

    /** 지정하면 실패 건만 따로 JSON으로 저장 — 배치 후 실패 목록을 손으로 뽑지 않아도 되게. */
    @Option(names = "--failures", paramLabel = "FILE",
            description = "실패 건만 JSON으로 저장 (단계·사유·원인 체인 포함)")
    Path failuresFile;

    /** true면 실패마다 스택 트레이스를 stderr로 남긴다(원인 체인만으로 부족할 때). */
    @Option(names = "--stacktrace", description = "실패 시 스택 트레이스도 출력")
    boolean stacktrace;

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

        // 판별은 배치 전체에서 딱 한 번만 돈다. 예전에는 OCR 사전 점검이 전 파일을 한 번 판별하고
        // extractOne이 파일마다 또 판별해, 수만 건 배치에서 파싱이 정확히 두 배로 들었다.
        ScanSurvey survey = ScanSurvey.of(files);
        ScanOcrConfig ocrConfig = ScanOcrConfig.fromProperties(props);
        ScanOcrRunner scanRunner = new ScanOcrRunner(ocrConfig);
        boolean ocrReady = checkOcrRunnerIfNeeded(survey, scanRunner, ocrConfig);
        LoadPolicy loadPolicy = LoadPolicy.fromProperties(props);

        List<SchemaResult> schemas = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        int ok = 0;
        for (ScanSurvey.Entry entry : survey.entries()) {
            Path file = entry.file();
            if (entry.status() == ScanSurvey.Status.FAILED) {
                // 판별 단계는 이미 갈래와 원인 체인을 갖고 있다 — 예외로 다시 감싸지 않는다
                failures.add(PipelineSupport.reportFailure(
                        file, "판별", entry.error(), entry.kind().name()));
                continue;
            }
            String stage = "추출";
            try {
                boolean scanned = entry.scanned();
                if (!ocrReady && scanned) {
                    throw new IOException("OCR 실행 스크립트가 없어 스캔본을 처리할 수 없습니다");
                }

                PipelineSupport.ExtractResult result = PipelineSupport.extractOne(
                        file, null, output, !noImages, scanRunner, scanned);

                stage = "표해석";
                // 표 해석은 한 번만 하고 중간 산출물 저장과 매핑이 함께 쓴다
                List<InterpretedTable> interpreted = TableInterpreter.interpret(
                        TableInterpreter.tablesOf(result.raw()));

                stage = "매핑";
                SchemaResult schema = loadPolicy.apply(
                        Mapper.mapToSchema(result.raw(), result.engine(), interpreted), result.raw());

                stage = "저장";
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
            } catch (Throwable t) {
                failures.add(PipelineSupport.reportFailure(file, stage, t, stacktrace));
            }
        }

        if (!noDb && !schemas.isEmpty()) {
            loadToDatabase(props, schemas);
        }
        if (failuresFile != null) {
            PipelineSupport.writeFailures(failures, files.size(), failuresFile);
        }

        System.out.printf("총 %d개 중 %d개 완료, %d개 실패%n", files.size(), ok, failures.size());
        return failures.isEmpty() ? 0 : 1;
    }

    /**
     * 스캔본이 1건 이상일 때만 OCR 스크립트 존재를 확인한다(§8) — 스캔본이 없으면 스크립트 없이도
     * 배치가 동작한다. 이미 끝난 판별 결과를 쓰므로 파일을 다시 열지 않는다.
     */
    private boolean checkOcrRunnerIfNeeded(ScanSurvey survey, ScanOcrRunner scanRunner,
                                           ScanOcrConfig ocrConfig) {
        if (survey.scanned() == 0) {
            return true;
        }
        boolean ready = scanRunner.isAvailable();
        if (!ready) {
            System.out.println("[경고] OCR 실행 스크립트를 찾을 수 없습니다(" + ocrConfig.script()
                    + "). 스캔본 " + survey.scanned() + "건은 [실패] 처리됩니다.");
        }
        return ready;
    }

    /** 커넥션 풀을 열어 스키마를 마이그레이션한 뒤 수집된 스키마 결과들을 documents/ref_files에 적재한다. */
    private void loadToDatabase(AppProperties props, List<SchemaResult> schemas) throws Exception {
        try (HikariDataSource dataSource = DataSourceFactory.create(props)) {
            DbSchema.migrate(dataSource);
            try (DbLoader loader = new DbLoader(dataSource)) {
                LoadStats stats = loader.loadAll(schemas);
                System.out.printf("DB 적재: %d개 파일, documents %d행, ref_files %d행 (적재제외 %d개)%n",
                        stats.filesOk(), stats.documentsInserted(), stats.refFilesInserted(),
                        stats.filesSkipped());
            }
        }
    }
}
