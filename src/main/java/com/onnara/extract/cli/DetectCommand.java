package com.onnara.extract.cli;

import com.onnara.extract.common.Json;
import com.onnara.extract.detect.DetectorRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** {@code detect}: 스캔 여부 일괄 분류 결과 출력 (§8). */
@Command(name = "detect", description = "스캔 여부 일괄 분류")
public class DetectCommand implements Callable<Integer> {

    @Parameters(paramLabel = "FILE", description = "검사할 파일 또는 폴더")
    List<Path> targets;

    @Option(names = "--json", description = "JSON 배열로 출력")
    boolean json;

    @Override
    public Integer call() throws Exception {
        List<Path> files = PipelineSupport.collectInputs(targets);
        List<Map<String, Object>> results = new ArrayList<>();
        int failed = 0;

        for (Path file : files) {
            try {
                boolean scanned = DetectorRegistry.isScanned(file);
                if (json) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("file", file.toString());
                    row.put("is_scanned", scanned);
                    results.add(row);
                } else {
                    System.out.println(file + ": " + (scanned ? "scanned" : "native"));
                }
            } catch (Exception e) {
                failed++;
                System.out.println("[실패] " + file + ": " + e.getMessage());
            }
        }

        if (json) {
            System.out.println(Json.PRETTY.writeValueAsString(results));
        }
        return failed == 0 ? 0 : 1;
    }
}
