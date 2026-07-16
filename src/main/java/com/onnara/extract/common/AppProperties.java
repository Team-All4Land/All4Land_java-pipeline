package com.onnara.extract.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Properties;

/**
 * classpath의 application.properties + 배포 디렉터리의 {@code .env} 로더 — 실행 설정의 단일 출처.
 *
 * <p>우선순위: OS 환경변수 &gt; {@code .env} 파일 &gt; application.properties.
 * 리눅스 서버 배포 시 접속·실행 정보를 코드/빌드 산출물이 아닌 {@code .env}로 관리하기 위해,
 * 환경변수 매핑이 필요한 키는 {@link #getWithEnv}로 조회한다.
 */
public final class AppProperties {

    /** 로드된 application.properties 키·값. */
    private final Properties props;

    /** {@code .env} 파일 키·값(리눅스 서버 배포용). OS 환경변수보다는 낮고 파일보다는 높은 우선순위. */
    private final Map<String, String> env;

    /** load()만 인스턴스를 만든다(외부 생성 방지). */
    private AppProperties(Properties props, Map<String, String> env) {
        this.props = props;
        this.env = env;
    }

    /** classpath의 application.properties와 {@code .env}를 읽어 로더를 만든다(둘 다 없으면 빈 설정). */
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
        return new AppProperties(p, DotEnv.load());
    }

    /** 파일 값. 없거나 공백이면 fallback. */
    public String get(String key, String fallback) {
        String v = props.getProperty(key);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    /** 환경변수 우선 조회: OS 환경변수 &gt; {@code .env} 파일 &gt; application.properties 값 &gt; fallback. */
    public String getWithEnv(String key, String fallback, String... envKeys) {
        for (String envKey : envKeys) {
            String osEnv = System.getenv(envKey);
            if (osEnv != null && !osEnv.isBlank()) {
                return osEnv.trim();
            }
            String dotEnv = env.get(envKey);
            if (dotEnv != null && !dotEnv.isBlank()) {
                return dotEnv.trim();
            }
        }
        return get(key, fallback);
    }
}
