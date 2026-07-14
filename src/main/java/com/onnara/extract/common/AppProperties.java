package com.onnara.extract.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * classpath의 application.properties 로더.
 *
 * <p>우선순위: CLI 명시값 &gt; 환경변수 &gt; 파일. CLI 값은 각 호출부에서
 * 넘겨받고, 환경변수 매핑은 {@link #get(String, String)} 호출 전에
 * {@link #getWithEnv}로 조회한다.
 */
public final class AppProperties {

    private final Properties props;

    private AppProperties(Properties props) {
        this.props = props;
    }

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
