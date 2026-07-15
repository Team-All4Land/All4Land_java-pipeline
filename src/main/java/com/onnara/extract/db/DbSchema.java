package com.onnara.extract.db;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/**
 * Flyway 마이그레이션 실행 — §6 DDL(V1__init.sql)을 버전 관리한다.
 *
 * <p>SQLite 시절의 런타임 {@code ALTER TABLE} 자동 추가 방식을 대체한다.
 * 스키마 변경은 {@code resources/db/migration/}에 새 버전 파일을 추가하는 방식으로 이력을 남긴다.
 */
public final class DbSchema {

    /** 인스턴스화 방지 — 정적 마이그레이션 함수만 제공하는 유틸리티 클래스. */
    private DbSchema() {
    }

    /** classpath의 db/migration 버전 파일을 순서대로 적용해 스키마를 최신화한다. */
    public static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
