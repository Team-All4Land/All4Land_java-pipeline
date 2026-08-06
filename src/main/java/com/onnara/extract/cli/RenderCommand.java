package com.onnara.extract.cli;

import com.onnara.extract.common.Errors;
import com.onnara.extract.common.Json;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.table.TableRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code render}: raw JSON의 표를 HTML로 되돌린다 — 추출 결과 눈으로 검수용.
 *
 * <p>{@code *.tables.json}(표 <b>해석</b> 결과: 라벨:값을 어떻게 읽었는가)과는 다른 산출물이다.
 * 이쪽은 <b>추출된 표 그 자체</b>를 원본 병합 구조 그대로 보여 준다 — 매핑이 비었을 때
 * "표를 잘못 읽었나"와 "사전에 라벨이 없나"를 가리려면 먼저 표가 제대로 뽑혔는지 봐야 한다.
 *
 * <p>이름이 {@code tables}가 아니라 {@code render}인 이유: 한 글자 차이인 두 서브커맨드는
 * 오타 한 번에 엉뚱한 명령이 돌아간다.
 */
@Command(name = "render", description = "raw JSON의 표를 HTML로 복원 (검수용)")
public class RenderCommand implements Callable<Integer> {

    /** 입력으로 인정하는 원시 JSON 접미사 — 폴더를 넘겼을 때 수집 기준이자 출력명 어간 기준. */
    private static final String RAW_SUFFIX = ".raw.json";

    /** 렌더링할 raw JSON 파일 또는 폴더(폴더는 *.raw.json만 재귀 수집). */
    @Parameters(paramLabel = "RAW_JSON", description = "raw JSON 파일 또는 폴더")
    List<Path> targets;

    /** HTML이 저장될 출력 폴더. */
    @Option(names = {"-o", "--output"}, required = true, description = "출력 폴더")
    Path outputDir;

    /**
     * 각 raw JSON을 읽어 {@code <이름>.tables.html}로 저장한다.
     * 파일 단위 실패는 [실패] 로그로 격리하며, 하나라도 실패하면 종료 코드 1.
     */
    @Override
    public Integer call() throws Exception {
        List<Path> files = collectRawJson(targets);
        if (files.isEmpty()) {
            System.out.println("처리할 raw JSON이 없습니다 (" + RAW_SUFFIX + " 파일을 지정하세요)");
            return 0;
        }

        int ok = 0;
        int failed = 0;
        for (Path file : files) {
            try {
                RawDocument raw = Json.MAPPER.readValue(file.toFile(), RawDocument.class);
                Path dest = outputDir.resolve(stemFor(file) + ".tables.html");
                if (dest.getParent() != null) {
                    Files.createDirectories(dest.getParent());
                }
                Files.writeString(dest, TableRenderer.toHtmlDocument(raw), StandardCharsets.UTF_8);
                ok++;
            } catch (Exception e) {
                failed++;
                System.out.println("[실패] " + file + ": " + Errors.describe(e));
            }
        }
        System.out.printf("총 %d개 중 %d개 완료, %d개 실패%n", files.size(), ok, failed);
        return failed == 0 ? 0 : 1;
    }

    /** 폴더는 {@code *.raw.json}만 재귀 수집하고, 파일은 그대로 받는다(정렬해 출력 순서를 고정). */
    private static List<Path> collectRawJson(List<Path> targets) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path target : targets) {
            if (Files.isDirectory(target)) {
                try (Stream<Path> walk = Files.walk(target)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(RAW_SUFFIX))
                            .forEach(files::add);
                }
            } else if (Files.isRegularFile(target)) {
                files.add(target);
            } else {
                System.out.println("[실패] " + target + ": 파일이 존재하지 않습니다");
            }
        }
        files.sort(java.util.Comparator.comparing(Path::toString));
        return files;
    }

    /** 출력 파일명 어간 — ".raw.json"이면 그 접미사를, 아니면 확장자만 벗긴다. */
    private static String stemFor(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(RAW_SUFFIX)
                ? name.substring(0, name.length() - RAW_SUFFIX.length())
                : PipelineSupport.stem(file);
    }
}
