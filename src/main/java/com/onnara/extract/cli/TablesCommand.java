package com.onnara.extract.cli;

import com.onnara.extract.common.Errors;
import com.onnara.extract.common.Json;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.table.TableDoc;
import com.onnara.extract.common.table.TableInterpreter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code tables}: raw JSON → 표 해석 JSON (중간 단계 전용).
 *
 * <p>표준 스키마는 "어느 필드가 무슨 값이 됐는가"만 남기므로, 값이 틀렸을 때
 * 표를 잘못 읽은 것인지 사전에 라벨이 없는 것인지 구분되지 않는다. 이 단계는
 * 그 사이를 드러낸다 — 표별 서식 판정, 읽어낸 라벨과 원본 좌표, 사전 매핑 여부.
 */
@Command(name = "tables", description = "raw JSON → 표 해석 JSON (중간 단계)")
public class TablesCommand implements Callable<Integer> {

    /** 출력 파일명 산정 시 벗겨낼 원시 JSON 접미사. */
    private static final String RAW_SUFFIX = ".raw.json";

    /** 해석할 raw JSON 파일 목록. */
    @Parameters(paramLabel = "RAW_JSON", description = "raw JSON 파일 목록")
    List<Path> rawFiles;

    /** 표 해석 JSON이 저장될 출력 폴더. */
    @Option(names = {"-o", "--output"}, required = true, description = "출력 폴더")
    Path outputDir;

    /** true면 파일별 미매핑 라벨을 콘솔에도 요약 출력한다. */
    @Option(names = "--summary", description = "미매핑 라벨 요약을 콘솔에 출력")
    boolean summary;

    /**
     * 각 raw JSON의 표를 해석해 {@code *.tables.json}으로 저장한다.
     * 파일 단위 실패는 [실패] 로그로 격리하며, 하나라도 실패하면 종료 코드 1.
     */
    @Override
    public Integer call() throws Exception {
        int ok = 0;
        int failed = 0;
        for (Path file : rawFiles) {
            try {
                RawDocument raw = Json.MAPPER.readValue(file.toFile(), RawDocument.class);
                TableDoc doc = TableInterpreter.interpret(raw, null);
                PipelineSupport.writeJson(doc, outputDir.resolve(stemFor(file) + ".tables.json"));
                if (summary) {
                    printSummary(doc);
                }
                ok++;
            } catch (Exception e) {
                failed++;
                System.out.println("[실패] " + file + ": " + Errors.describe(e));
            }
        }
        System.out.printf("총 %d개 중 %d개 완료, %d개 실패%n", rawFiles.size(), ok, failed);
        return failed == 0 ? 0 : 1;
    }

    /** 표 개수·매핑 건수와 미매핑 라벨을 한 줄로 요약한다. */
    private static void printSummary(TableDoc doc) {
        TableDoc.Summary s = doc.summary();
        System.out.printf("%s: 표 %d개, 라벨 %d건(매핑 %d / 미매핑 %d)%n",
                doc.sourceFile(), s.tableCount(), s.factCount(), s.mappedCount(), s.unmappedCount());
        if (!s.unmappedLabels().isEmpty()) {
            System.out.println("  미매핑 라벨: " + String.join(", ", s.unmappedLabels()));
        }
    }

    /** 출력 파일명 어간 — ".raw.json"이면 그 접미사를, 아니면 확장자만 벗긴다. */
    private static String stemFor(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(RAW_SUFFIX)
                ? name.substring(0, name.length() - RAW_SUFFIX.length())
                : PipelineSupport.stem(file);
    }
}
