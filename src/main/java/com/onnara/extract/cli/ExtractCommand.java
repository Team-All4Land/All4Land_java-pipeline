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

    /** 추출할 파일 또는 폴더(폴더는 지원 확장자만 재귀 수집). */
    @Parameters(paramLabel = "FILE", description = "추출할 파일 또는 폴더")
    List<Path> targets;

    /** 스키마/원시 JSON과 images/가 저장될 출력 폴더. */
    @Option(names = {"-o", "--output"}, required = true, description = "출력 폴더")
    Path outputDir;

    /** true면 스키마 JSON과 함께 원시 결과(*.raw.json)도 저장. */
    @Option(names = "--raw", description = "원시 결과(*.raw.json)도 함께 저장")
    boolean raw;

    /** true면 이미지 파일 저장을 생략. */
    @Option(names = "--no-images", description = "이미지 저장 생략")
    boolean noImages;

    /** true면 문서에 삽입된 이미지의 OCR을 생략한다(추론 시간 단축용). */
    @Option(names = "--no-image-ocr",
            description = "문서에 삽입된 사진·위치도의 OCR 생략 (스캔본 처리에는 영향 없음)")
    boolean noImageOcr;

    /** 지정 시 스캔 판별을 건너뛰고 해당 엔진으로 강제 추출. */
    @Option(names = "--engine", description = "엔진 강제 지정 (예: hwplib, owpml, hml-dom, pdfbox)")
    String engine;

    /**
     * 각 파일을 추출·매핑해 out/&lt;이름&gt;.schema.json(+--raw 시 raw.json)을 저장한다.
     * 파일 단위 실패는 [실패] 로그로 격리하며, 하나라도 실패하면 종료 코드 1.
     */
    @Override
    public Integer call() throws Exception {
        List<Path> files = PipelineSupport.collectInputs(targets);
        AppProperties props = AppProperties.load();
        ScanOcrConfig ocrConfig = ScanOcrConfig.fromProperties(props);
        ScanOcrRunner scanRunner = new ScanOcrRunner(noImageOcr ? ocrConfig.withoutImageOcr() : ocrConfig);
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
