package com.onnara.extract.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * classpath의 application.properties 로더 — 실행 설정의 단일 출처.
 *
 * <p>우선순위: 환경변수 &gt; 파일. 환경변수 매핑이 필요한 키(비밀번호 등)만
 * {@link #getWithEnv}로 조회한다.
 */
public final class AppProperties {

    /** 로드된 application.properties 키·값. */
    private final Properties props;

    /** load()만 인스턴스를 만든다(외부 생성 방지). */
    private AppProperties(Properties props) {
        this.props = props;
    }

    /** classpath에서 application.properties를 읽어 로더를 만든다(파일이 없으면 빈 설정). */
    public static AppProperties load() {
        Properties p = new Properties();
        try (InputStream in = AppProperties.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("application.properties 로드 실패", e);
        }
        return new AppProperties(p);
    }

    /** 파일 값. 없거나 공백이면 fallback. */
    public String get(String key, String fallback) {
        String v = props.getProperty(key);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    /** 환경변수 우선 조회: envKeys 중 먼저 설정된 값 &gt; 파일 값 &gt; fallback. */
    public String getWithEnv(String key, String fallback, String... envKeys) {
        for (String envKey : envKeys) {
            String env = System.getenv(envKey);
            if (env != null && !env.isBlank()) {
                return env.trim();
            }
        }
        return get(key, fallback);
    }
}
