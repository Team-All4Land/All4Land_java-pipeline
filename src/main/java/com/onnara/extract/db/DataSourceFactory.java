package com.onnara.extract.db;

import com.onnara.extract.common.AppProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * PostgreSQL HikariCP 커넥션 풀 생성 — application.properties 기반.
 *
 * <p>비밀번호만 환경변수({@code PGPASSWORD}/{@code DB_PASSWORD})가 파일 값보다
 * 우선한다. 평문 비밀번호를 파일에 두지 않는 것을 권장한다(§8).
 */
public final class DataSourceFactory {

    private DataSourceFactory() {
    }

    public static HikariDataSource create(AppProperties props) {
        String url = props.get("db.url", "jdbc:postgresql://localhost:5432/extract");
        String user = props.get("db.user", "extract");
        String password = props.getWithEnv("db.password", "", "PGPASSWORD", "DB_PASSWORD");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(Integer.parseInt(props.get("db.pool.max-size", "5")));
        config.setPoolName("extract-pipeline");
        return new HikariDataSource(config);
    }
}
