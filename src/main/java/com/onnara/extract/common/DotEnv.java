package com.onnara.extract.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code .env} 파일 로더 — 리눅스 서버 배포 시 환경변수를 파일로 관리하기 위한 경량 파서.
 *
 * <p>형식: 한 줄에 {@code KEY=VALUE}. {@code #}로 시작하는 주석 줄과 빈 줄은 무시하고,
 * 선택적 {@code export } 접두사와 값 양쪽을 감싼 따옴표를 제거한다.
 *
 * <p>파일 위치: 환경변수 {@code APP_ENV_FILE} 경로 &gt; 현재 작업 디렉터리의 {@code .env}.
 * 파일이 없으면 빈 맵을 돌려주므로 {@code .env} 없이도 실행은 정상 동작한다.
 * OS 환경변수를 대체하지 않고 보완하는 용도이며, 우선순위는 {@link AppProperties}가 정한다.
 */
public final class DotEnv {

    /** 인스턴스화 방지 — 정적 로더만 제공한다. */
    private DotEnv() {
    }

    /** 기본 위치({@code APP_ENV_FILE} 경로 또는 {@code .env})에서 키·값을 읽는다. */
    public static Map<String, String> load() {
        String override = System.getenv("APP_ENV_FILE");
        Path path = (override != null && !override.isBlank())
                ? Path.of(override.trim())
                : Path.of(".env");
        return load(path);
    }

    /** 지정 경로의 {@code .env}를 파싱한다(파일이 없으면 빈 맵). */
    public static Map<String, String> load(Path path) {
        Map<String, String> env = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            return env;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException(".env 로드 실패: " + path, e);
        }
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = unquote(line.substring(eq + 1).trim());
            env.put(key, value);
        }
        return env;
    }

    /** 값 양쪽을 감싼 짝따옴표/홑따옴표 한 쌍을 제거한다. */
    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
