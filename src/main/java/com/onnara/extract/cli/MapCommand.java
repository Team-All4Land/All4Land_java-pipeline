package com.onnara.extract.cli;

import com.onnara.extract.common.Json;
import com.onnara.extract.common.Mapper;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.SchemaResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/** {@code map}: raw JSON → 스키마 JSON 매핑 전용 (§8). */
@Command(name = "map", description = "raw JSON → 스키마 JSON 매핑 전용")
public class MapCommand implements Callable<Integer> {

    private static final String RAW_SUFFIX = ".raw.json";

    @Parameters(paramLabel = "RAW_JSON", description = "raw JSON 파일 목록")
    List<Path> rawFiles;

    @Option(names = {"-o", "--output"}, required = true, description = "출력 폴더")
    Path outputDir;

    @Override
    public Integer call() throws Exception {
        int ok = 0;
        int failed = 0;
        for (Path file : rawFiles) {
            try {
                RawDocument raw = Json.MAPPER.readValue(file.toFile(), RawDocument.class);
                SchemaResult schema = Mapper.mapToSchema(raw);
                PipelineSupport.writeJson(schema, outputDir.resolve(stemFor(file) + ".schema.json"));
                ok++;
            } catch (Exception e) {
                failed++;
                System.out.println("[실패] " + file + ": " + e.getMessage());
            }
        }
        System.out.printf("총 %d개 중 %d개 완료, %d개 실패%n", rawFiles.size(), ok, failed);
        return failed == 0 ? 0 : 1;
    }

    private static String stemFor(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(RAW_SUFFIX)
                ? name.substring(0, name.length() - RAW_SUFFIX.length())
                : PipelineSupport.stem(file);
    }
}
