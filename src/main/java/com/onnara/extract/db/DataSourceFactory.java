package com.onnara.extract.db;

import com.onnara.extract.common.AppProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * PostgreSQL HikariCP 커넥션 풀 생성 — application.properties 기반.
 *
 * <p>접속 정보는 OS 환경변수 &gt; {@code .env} 파일 &gt; application.properties 순으로 우선한다.
 * 리눅스 서버 배포 시 {@code DB_URL}/{@code DB_USER}/{@code DB_PASSWORD} 등을 {@code .env}로
 * 관리하며, 평문 비밀번호를 파일에 두지 않는 것을 권장한다(§8).
 */
public final class DataSourceFactory {

    private DataSourceFactory() {
    }

    public static HikariDataSource create(AppProperties props) {
        String url = props.getWithEnv("db.url", "jdbc:postgresql://localhost:5432/extract", "DB_URL");
        String user = props.getWithEnv("db.user", "extract", "DB_USER");
        String password = props.getWithEnv("db.password", "", "PGPASSWORD", "DB_PASSWORD");
        int poolMaxSize = Integer.parseInt(
                props.getWithEnv("db.pool.max-size", "5", "DB_POOL_MAX_SIZE"));

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolMaxSize);
        config.setPoolName("extract-pipeline");
        return new HikariDataSource(config);
    }
}
