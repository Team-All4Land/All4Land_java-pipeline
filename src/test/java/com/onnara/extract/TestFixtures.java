package com.onnara.extract;

import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;

/** samples/ 폴더의 실제 고시문 픽스처 로더. 파일이 없으면 테스트를 skip한다. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static Path sample(String name) {
        Path path = Path.of("samples", name);
        Assumptions.assumeTrue(Files.exists(path), "픽스처 없음: " + path);
        return path;
    }
}
