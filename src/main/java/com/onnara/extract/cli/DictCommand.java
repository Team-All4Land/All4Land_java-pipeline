package com.onnara.extract.cli;

import com.onnara.extract.docs.DbStandardDoc;
import com.onnara.extract.docs.ExtrasReport;
import com.onnara.extract.docs.NoticeTypesReport;
import com.onnara.extract.docs.SynonymsDoc;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code dict}: 동의어 사전과 검토 문서(미매핑 라벨·미분류 제목)를 생성한다 (§9).
 *
 * <p>사전 본문은 {@code src/main/resources/synonyms.json}과 {@code notice_types.json}이고,
 * 여기서 만드는 문서는 그 파생물이다 — 검토자가 JSON이나 Java를 열지 않고도 무엇이 어떻게
 * 매핑되는지, 무엇이 아직 매핑되지 않는지 확인할 수 있게 한다.
 *
 * <p>검토 리포트가 둘인 이유: 두 사전의 구멍이 서로 다른 곳에서 드러난다. 라벨이 모자라면
 * 값이 {@code extras}로 새고, 공고종류가 모자라면 {@code NOTI_KND_CD}가 빈다.
 *
 * <p>DB 표준 사전({@code db/standard_terms.json})도 여기서 함께 렌더링한다 — 사전을 고치고
 * 문서를 따로 갱신하게 두면 반드시 어긋나므로, 생성 지점을 하나로 모은다.
 */
@Command(name = "dict", description = "동의어 사전·검토 리포트 생성")
public class DictCommand implements Callable<Integer> {

    /** 스키마 결과 파일 접미사 — 검토 리포트의 입력. */
    private static final String SCHEMA_SUFFIX = ".schema.json";

    /** 동의어 사전 문서 출력 경로. */
    @Option(names = {"-o", "--output"}, defaultValue = "docs/SYNONYMS.md",
            description = "동의어 사전 문서 경로 (기본 ${DEFAULT-VALUE})")
    Path output;

    /** 지정하면 이 폴더의 *.schema.json을 집계해 미매핑 라벨 검토 리포트도 만든다. */
    @Option(names = "--review", description = "검토 리포트를 만들 스키마 JSON 폴더")
    Path reviewDir;

    /** 미매핑 라벨 검토 리포트 출력 경로. */
    @Option(names = "--review-output", defaultValue = "docs/EXTRAS_REVIEW.md",
            description = "미매핑 라벨 리포트 경로 (기본 ${DEFAULT-VALUE})")
    Path reviewOutput;

    /** 미분류 제목 검토 리포트 출력 경로. */
    @Option(names = "--types-output", defaultValue = "docs/NOTICE_TYPES_REVIEW.md",
            description = "미분류 제목 리포트 경로 (기본 ${DEFAULT-VALUE})")
    Path typesOutput;

    /** DB 표준 사전 문서 출력 경로. */
    @Option(names = "--db-standard", defaultValue = "docs/DB_STANDARD.md",
            description = "DB 표준 사전 문서 경로 (기본 ${DEFAULT-VALUE})")
    Path dbStandardOutput;

    /** 사전 문서를 생성하고, --review가 있으면 검토 리포트도 생성한다. */
    @Override
    public Integer call() throws Exception {
        write(output, SynonymsDoc.render());
        System.out.println("동의어 사전 문서: " + output);

        write(dbStandardOutput, DbStandardDoc.render());
        System.out.println("DB 표준 사전 문서: " + dbStandardOutput);

        if (reviewDir == null) {
            return 0;
        }
        List<Path> schemas = collectSchemas(reviewDir);
        if (schemas.isEmpty()) {
            System.out.println("[경고] " + reviewDir + "에 *" + SCHEMA_SUFFIX + " 파일이 없어 "
                    + "검토 리포트를 건너뜁니다.");
            return 0;
        }
        write(reviewOutput, ExtrasReport.render(schemas));
        System.out.printf("미매핑 라벨 검토 리포트: %s (문서 %d개 집계)%n", reviewOutput, schemas.size());

        write(typesOutput, NoticeTypesReport.render(schemas));
        System.out.printf("미분류 제목 검토 리포트: %s (문서 %d개 집계)%n", typesOutput, schemas.size());
        return 0;
    }

    /** 폴더에서 스키마 JSON을 재귀 수집해 경로순으로 정렬한다. */
    private static List<Path> collectSchemas(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("폴더가 아닙니다: " + dir);
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(SCHEMA_SUFFIX))
                    .sorted()
                    .toList();
        }
    }

    /** 문서를 UTF-8로 저장한다(상위 폴더는 자동 생성). */
    private static void write(Path dest, String content) throws IOException {
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        Files.writeString(dest, content, StandardCharsets.UTF_8);
    }
}
