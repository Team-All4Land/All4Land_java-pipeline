package com.onnara.extract.cli;

import com.onnara.extract.common.AgencyRegistry;
import com.onnara.extract.common.AppProperties;
import com.onnara.extract.common.LoadPolicy;
import com.onnara.extract.common.LoadStep;
import com.onnara.extract.common.Mapper;
import com.onnara.extract.common.SourceFileName;
import com.onnara.extract.common.model.SchemaResult;
import com.onnara.extract.common.table.InterpretedTable;
import com.onnara.extract.common.table.TableDoc;
import com.onnara.extract.common.table.TableInterpreter;
import com.onnara.extract.db.DataSourceFactory;
import com.onnara.extract.db.DbLoader;
import com.onnara.extract.db.DbSchema;
import com.onnara.extract.db.LoadStats;
import com.onnara.extract.db.ReferenceSync;
import com.onnara.extract.detect.ScanSurvey;
import com.onnara.extract.docs.UnmappedReport;
import com.onnara.extract.scan.ScanOcrConfig;
import com.onnara.extract.scan.ScanOcrRunner;
import com.zaxxer.hikari.HikariDataSource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *
 * <p>입력 폴더 바로 아래의 디렉터리 하나가 기관 게시판 하나다
 * ({@code {번호}[_{하위번호}]_{기관명}[_{게시판구분}]}, {@link AgencyRegistry} 참고).
 * 첨부파일 안에는 수집처가 없으므로 이 폴더명이 유일한 근거다.
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

    /**
     * true면 DB에 값을 한 줄도 못 넣은 첨부마다 {@code <이름>.unmapped.json}을 남긴다.
     *
     * <p>해당 없는 첨부는 파일을 만들지 않는다 — 1,800개 중 32개가 대상이라 전부 만들면
     * 정작 봐야 할 32개가 빈 파일 더미에 묻힌다.
     */
    @Option(names = "--unmapped",
            description = "DB에 값이 하나도 안 들어간 첨부마다 미적재 내역 JSON 저장")
    boolean unmapped;

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
        // 기관 확정은 배치 시작에 한 번. 번호 발급이 폴더 집합 전체를 봐야 정해진다
        AgencyRegistry registry = AgencyRegistry.scan(input);
        warnOnDuplicateKeys(files);

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
                        file, LoadStep.DETECT, entry.error(), entry.kind().name()));
                continue;
            }
            LoadStep step = LoadStep.EXTRACT;
            try {
                boolean scanned = entry.scanned();
                if (!ocrReady && scanned) {
                    throw new IOException("OCR 실행 스크립트가 없어 스캔본을 처리할 수 없습니다");
                }

                PipelineSupport.ExtractResult result = PipelineSupport.extractOne(
                        file, null, output, !noImages, scanRunner, scanned);

                step = LoadStep.TABLE;
                // 표 해석은 한 번만 하고 중간 산출물 저장과 매핑이 함께 쓴다
                List<InterpretedTable> interpreted = TableInterpreter.interpret(
                        TableInterpreter.tablesOf(result.raw()));

                step = LoadStep.MAP;
                SchemaResult schema = loadPolicy.apply(
                        Mapper.mapToSchema(result.raw(), result.engine(), interpreted), result.raw());
                // 수집처는 경로에만 있다 — Mapper는 파일명만 받으므로 여기서 붙인다
                schema.setSourceBoard(registry.boardOf(file).orElse(null));

                step = LoadStep.SAVE;
                String stem = PipelineSupport.stem(file);
                if (raw) {
                    PipelineSupport.writeJson(result.raw(), output.resolve(stem + ".raw.json"));
                }
                if (tables) {
                    PipelineSupport.writeJson(
                            TableDoc.of(result.raw().getAtchFileNm(), result.raw().getFileExtnNm(),
                                    result.raw().isScanYn(), result.engine(), interpreted),
                            output.resolve(stem + ".tables.json"));
                }
                PipelineSupport.writeJson(schema, output.resolve(stem + ".schema.json"));
                if (unmapped) {
                    UnmappedReport.of(schema, result.raw()).ifPresent(row -> {
                        try {
                            PipelineSupport.writeJson(row, output.resolve(stem + ".unmapped.json"));
                        } catch (IOException e) {
                            // 리포트를 못 써도 적재는 계속한다 — 진단 산출물이 본 작업을 막으면 안 된다
                            System.out.println("[경고] 미적재 내역을 남기지 못했습니다: " + stem);
                        }
                    });
                }
                schemas.add(schema);
                ok++;
            } catch (Throwable t) {
                failures.add(PipelineSupport.reportFailure(file, step, t, stacktrace));
            }
        }

        if (!noDb && !(schemas.isEmpty() && failures.isEmpty() && registry.agencies().isEmpty())) {
            loadToDatabase(props, registry, schemas, failures);
        }
        if (failuresFile != null) {
            PipelineSupport.writeFailures(failures, files.size(), failuresFile);
        }

        System.out.printf("총 %d개 중 %d개 완료, %d개 실패%n", files.size(), ok, failures.size());
        return failures.isEmpty() ? 0 : 1;
    }

    /**
     * 한 배치 안에서 적재 키가 겹치는 파일을 미리 경고한다.
     *
     * <p>{@code (순번, 첨부순번)}이 같은 파일이 둘이면 뒤 파일이 앞 파일의 첨부 행을 지우고
     * 그 자리에 앉는다 — 멱등 재적재와 구분할 방법이 없어 <b>조용히</b> 사라진다.
     * 파일명 규약이 어긋나는 순간 데이터가 소리 없이 없어지는 자리라 배치 시작에 한 번 훑는다.
     *
     * <p>{@link SourceFileName#peek}를 쓴다 — {@code parse}를 쓰면 크롤 패턴이 아닌 파일마다
     * 음수 순번이 여기서 한 개씩 소모돼 매핑 단계가 받는 번호와 어긋난다.
     */
    private static void warnOnDuplicateKeys(List<Path> files) {
        Map<String, Path> seen = new LinkedHashMap<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            SourceFileName.peek(name).ifPresent(parsed -> {
                String key = parsed.notiSn() + "_" + parsed.atchSn();
                Path previous = seen.putIfAbsent(key, file);
                if (previous != null) {
                    System.out.println("[경고] 적재 키(" + key + ")가 겹칩니다 — 뒤 파일이 앞 파일을 "
                            + "덮어씁니다: " + previous + " ↔ " + file);
                }
            });
        }
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

    /**
     * 커넥션 풀을 열어 스키마를 최신화하고 3계층으로 적재한다.
     *
     * <p>{@link ReferenceSync}는 반드시 적재보다 먼저 돈다 — {@code OS_NOTI_ITEM_VAL_DTL}이
     * {@code OS_NOTI_ITEM_TC}을 참조하므로 순서가 뒤바뀌면 첫 적재가 FK 위반으로 실패한다.
     *
     * <p>실패 건도 함께 넘긴다. 성공한 파일만 적재하면 "첨부 401건 중 추출 0건"인 기관이
     * DB에서 아예 보이지 않아, 추출 누락과 애초에 없던 자료를 구분할 수 없다.
     */
    private void loadToDatabase(AppProperties props, AgencyRegistry registry,
                                List<SchemaResult> schemas,
                                List<Map<String, Object>> failures) throws Exception {
        try (HikariDataSource dataSource = DataSourceFactory.create(props)) {
            DbSchema.migrate(dataSource);
            ReferenceSync.Counts reference = ReferenceSync.sync(dataSource);
            try (DbLoader loader = new DbLoader(dataSource)) {
                // 파일이 0건인 폴더도 기관으로 남긴다 — 그래야 "긁었는데 아무것도 못 건진 기관"이
                // DB에서 보인다. 첨부보다 먼저 넣어야 OS_NOTI_BAS.INSTT_SN FK가 걸리지 않는다
                int agencies = loader.loadAgencies(registry.agencies());
                LoadStats stats = loader.loadAll(schemas, toFailedAttachments(registry, failures));
                System.out.printf("사전 동기화: 표준항목 %d종, 공고종류 %d종, 기관 %d곳%n",
                        reference.attributes(), reference.noticeTypes(), agencies);
                System.out.printf(
                        "DB 적재: 첨부 %d건, 항목값 %d행, 라벨값 %d행, 이미지 %d행"
                                + " (적재제외 %d건, 실패기록 %d건)%n",
                        stats.filesOk(), stats.recordsInserted(), stats.labelsInserted(),
                        stats.imagesInserted(), stats.filesSkipped(), failures.size());
            }
        }
    }

    /**
     * {@code --failures} 산출물과 같은 모양의 실패 행을 적재용 레코드로 옮긴다.
     *
     * <p>실패 행의 {@code file}은 파일명이 아니라 <b>전체 경로</b>라 수집처를 되찾을 수 있다.
     * 판별·추출에 실패해도 게시물 행은 만들어지므로 기관이 붙어야 한다 — 안 붙이면 그 기관의
     * 실패 건이 "기관 미상"으로 새어 기관별 수집률이 실제보다 좋아 보인다.
     */
    private static List<DbLoader.FailedAttachment> toFailedAttachments(
            AgencyRegistry registry, List<Map<String, Object>> failures) {
        List<DbLoader.FailedAttachment> rows = new ArrayList<>();
        for (Map<String, Object> failure : failures) {
            Path file = Path.of(String.valueOf(failure.get("file")));
            rows.add(new DbLoader.FailedAttachment(
                    file.getFileName().toString(),
                    text(failure.get("stage")),
                    text(failure.get("kind")),
                    text(failure.get("message")),
                    registry.boardOf(file).orElse(null)));
        }
        return rows;
    }

    /** 실패 행의 값을 문자열로 — 없으면 null(컬럼을 비운다). */
    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
