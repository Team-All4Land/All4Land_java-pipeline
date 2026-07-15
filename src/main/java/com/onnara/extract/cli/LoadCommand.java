package com.onnara.extract.cli;

import com.onnara.extract.common.AppProperties;
import com.onnara.extract.common.Json;
import com.onnara.extract.common.model.SchemaResult;
import com.onnara.extract.db.DataSourceFactory;
import com.onnara.extract.db.DbLoader;
import com.onnara.extract.db.DbSchema;
import com.onnara.extract.db.LoadStats;
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
                System.out.println("[실패] " + file + ": " + e.getMessage());
            }
        }

        try (HikariDataSource dataSource = DataSourceFactory.create(props)) {
            DbSchema.migrate(dataSource);
            try (DbLoader loader = new DbLoader(dataSource)) {
                LoadStats stats = loader.loadAll(schemas);
                System.out.printf(
                        "총 %d개 중 %d개 완료, %d개 실패 (documents %d행, ref_files %d행)%n",
                        schemaFiles.size(), stats.filesOk(), stats.filesFailed() + readFailed,
                        stats.documentsInserted(), stats.refFilesInserted());
                return (stats.filesFailed() + readFailed) == 0 ? 0 : 1;
            }
        }
    }
}
