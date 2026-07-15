package com.onnara.extract.cli;

import com.onnara.extract.common.AppProperties;
import com.onnara.extract.common.Mapper;
import com.onnara.extract.common.model.SchemaResult;
import com.onnara.extract.engine.Extractor;
import com.onnara.extract.engine.ExtractorRegistry;
import com.onnara.extract.scan.ScanOcrConfig;
import com.onnara.extract.scan.ScanOcrRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/** {@code extract}: 형식/엔진 지정 추출 + 매핑 (DB 적재 없음) (§8). */
@Command(name = "extract", description = "형식/엔진 지정 추출 (+매핑)")
public class ExtractCommand implements Callable<Integer> {

    @Parameters(paramLabel = "FILE", description = "추출할 파일 또는 폴더")
    List<Path> targets;

    @Option(names = {"-o", "--output"}, required = true, description = "출력 폴더")
    Path outputDir;

    @Option(names = "--raw", description = "원시 결과(*.raw.json)도 함께 저장")
    boolean raw;

    @Option(names = "--no-images", description = "이미지 저장 생략")
    boolean noImages;

    @Option(names = "--engine", description = "엔진 강제 지정 (예: hwplib, owpml, hml-dom, pdfbox)")
    String engine;

    @Override
    public Integer call() throws Exception {
        List<Path> files = PipelineSupport.collectInputs(targets);
        AppProperties props = AppProperties.load();
        ScanOcrRunner scanRunner = new ScanOcrRunner(ScanOcrConfig.fromProperties(props));
        Extractor forcedExtractor = engine != null ? ExtractorRegistry.forEngineName(engine) : null;

        int ok = 0;
        int failed = 0;
        for (Path file : files) {
            try {
                PipelineSupport.ExtractResult result = PipelineSupport.extractOne(
                        file, forcedExtractor, outputDir, !noImages, scanRunner);
                SchemaResult schema = Mapper.mapToSchema(result.raw(), result.engine());

                String stem = PipelineSupport.stem(file);
                if (raw) {
                    PipelineSupport.writeJson(result.raw(), outputDir.resolve(stem + ".raw.json"));
                }
                PipelineSupport.writeJson(schema, outputDir.resolve(stem + ".schema.json"));
                ok++;
            } catch (Exception e) {
                failed++;
                System.out.println("[실패] " + file + ": " + e.getMessage());
            }
        }

        System.out.printf("총 %d개 중 %d개 완료, %d개 실패%n", files.size(), ok, failed);
        return failed == 0 ? 0 : 1;
    }
}
