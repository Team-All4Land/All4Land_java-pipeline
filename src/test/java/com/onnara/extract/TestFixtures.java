package com.onnara.extract;

import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;

/** samples/ 폴더의 실제 고시문 픽스처 로더. 파일이 없으면 테스트를 skip한다. */
public final class TestFixtures {

    /** 인스턴스화 방지 — 정적 로더만 제공하는 테스트 헬퍼. */
    private TestFixtures() {
    }

    /** samples/name 경로를 반환한다. 파일이 없으면 assumeTrue로 테스트를 skip시킨다. */
    public static Path sample(String name) {
        Path path = Path.of("samples", name);
        Assumptions.assumeTrue(Files.exists(path), "픽스처 없음: " + path);
        return path;
    }
}
