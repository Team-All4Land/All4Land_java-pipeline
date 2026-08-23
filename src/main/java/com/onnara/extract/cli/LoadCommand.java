package com.onnara.extract.cli;

import com.onnara.extract.common.AppProperties;
import com.onnara.extract.common.Errors;
import com.onnara.extract.common.Json;
import com.onnara.extract.common.model.SchemaResult;
import com.onnara.extract.db.DataSourceFactory;
import com.onnara.extract.db.DbLoader;
import com.onnara.extract.db.DbSchema;
import com.onnara.extract.db.LoadStats;
import com.onnara.extract.db.ReferenceSync;
import com.zaxxer.hikari.HikariDataSource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code load}: 스키마 JSON → PostgreSQL 적재 전용 — 재적재·스키마 변경 시 단독 실행 (§8).
 *
 * <p>DB 접속 정보는 application.properties에서만 읽는다(로컬 실행 전제).
 *
 * <p>기관은 스키마 JSON의 {@code source_board}에 실려 온 것만 넣는다 — 이 경로는 입력 폴더를
 * 보지 않기 때문이다. 첨부가 0건인 기관까지 남기려면 {@code pipeline}으로 돌려야 한다.
 */
@Command(name = "load", description = "스키마 JSON → PostgreSQL 적재 전용")
public class LoadCommand implements Callable<Integer> {

    /** 적재할 스키마 JSON 파일 목록. */
    @Parameters(paramLabel = "SCHEMA_JSON", description = "스키마 JSON 파일 목록")
    List<Path> schemaFiles;

    /**
     * 스키마 JSON들을 읽어 PostgreSQL에 적재한다(스키마 마이그레이션 후 멱등 적재).
     * 읽기·적재 실패는 파일 단위로 격리하며, 하나라도 실패하면 종료 코드 1.
     */
    @Override
    public Integer call() throws Exception {
        AppProperties props = AppProperties.load();
        List<SchemaResult> schemas = new ArrayList<>();
        int readFailed = 0;
        for (Path file : schemaFiles) {
            try {
                schemas.add(Json.MAPPER.readValue(file.toFile(), SchemaResult.class));
            } catch (Exception e) {
                readFailed++;
                System.out.println("[실패] " + file + ": " + Errors.describe(e));
            }
        }

        try (HikariDataSource dataSource = DataSourceFactory.create(props)) {
            DbSchema.migrate(dataSource);
            // 사전 동기화가 적재보다 먼저다 — NOTI_ITEM_VAL_DTL이 NOTI_ITEM_TC을 참조한다
            ReferenceSync.sync(dataSource);
            try (DbLoader loader = new DbLoader(dataSource)) {
                // 이 경로는 이미 만들어진 스키마 JSON만 읽으므로 판별·추출 실패 목록이 없다
                LoadStats stats = loader.loadAll(schemas, List.of());
                System.out.printf(
                        "총 %d개 중 %d개 완료, %d개 실패, %d개 적재제외 "
                                + "(기관 %d곳, 항목값 %d행, 이미지 %d행)%n",
                        schemaFiles.size(), stats.filesOk(), stats.filesFailed() + readFailed,
                        stats.filesSkipped(), stats.agenciesUpserted(),
                        stats.recordsInserted(), stats.imagesInserted());
                return (stats.filesFailed() + readFailed) == 0 ? 0 : 1;
            }
        }
    }
}
