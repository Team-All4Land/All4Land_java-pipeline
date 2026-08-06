package com.onnara.extract.cli;

import com.onnara.extract.common.Json;
import com.onnara.extract.detect.ScanSurvey;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code detect}: 스캔 여부 일괄 분류와 집계 (§8).
 *
 * <p>판별({@link ScanSurvey})만 돌린다 — 추출·매핑·DB 적재는 물론 OCR 서브프로세스도
 * 띄우지 않으므로, 코퍼스 전체에 돌려 "스캔본이 몇 건인가"를 먼저 확인하는 데 쓴다.
 * 파일 목록 없이 개수만 필요하면 {@code --summary}.
 */
@Command(name = "detect", description = "스캔 여부 일괄 분류·집계 (추출·OCR 없음)")
public class DetectCommand implements Callable<Integer> {

    /** 검사할 파일 또는 폴더(폴더는 지원 확장자만 재귀 수집). */
    @Parameters(paramLabel = "FILE", description = "검사할 파일 또는 폴더")
    List<Path> targets;

    /** true면 결과를 사람용 텍스트 대신 JSON으로 출력. */
    @Option(names = "--json", description = "JSON으로 출력")
    boolean json;

    /** true면 파일별 결과를 생략하고 집계만 출력 — 전체 문서 중 스캔본 개수만 볼 때. */
    @Option(names = "--summary", description = "파일별 결과 없이 집계만 출력")
    boolean summaryOnly;

    /** 지정하면 판별 실패 건만 따로 JSON으로 저장 — 수백 건을 오프라인에서 분류·재처리할 때. */
    @Option(names = "--failures", paramLabel = "FILE",
            description = "판별 실패 건만 JSON으로 저장 (갈래·사유·원인 체인 포함)")
    Path failuresFile;

    /** 실패 갈래마다 집계 출력에 함께 드는 예시 파일 수. */
    private static final int FAILURE_SAMPLES = 3;

    /**
     * 각 파일의 스캔 여부를 판별해 출력하고 마지막에 집계를 낸다(텍스트 또는 --json).
     * 판별 실패 파일은 [실패]로 격리해 집계의 failed로 세며, 하나라도 실패하면 종료 코드 1.
     */
    @Override
    public Integer call() throws Exception {
        List<Path> files = PipelineSupport.collectInputs(targets);
        ScanSurvey survey = ScanSurvey.of(files);

        if (json) {
            System.out.println(Json.PRETTY.writeValueAsString(toJsonReport(survey)));
        } else {
            printText(survey);
        }
        if (failuresFile != null) {
            PipelineSupport.writeJson(toFailureReport(survey), failuresFile);
            System.out.println("판별 실패 " + survey.failed() + "건을 " + failuresFile + "에 저장했습니다");
        }
        return survey.failed() == 0 ? 0 : 1;
    }

    /** 사람용 출력 — 파일별 한 줄(생략 가능) 뒤에 집계 블록. */
    private void printText(ScanSurvey survey) {
        if (!summaryOnly) {
            for (ScanSurvey.Entry entry : survey.entries()) {
                if (entry.status() == ScanSurvey.Status.FAILED) {
                    System.out.println("[실패] " + entry.file() + ": " + entry.error());
                } else {
                    System.out.println(entry.file() + ": " + entry.status().label());
                }
            }
            System.out.println();
        }

        System.out.printf("[집계] 총 %d개 — 스캔본 %d개(%s), 네이티브 %d개(%s), 판별 실패 %d개%n",
                survey.total(),
                survey.scanned(), percent(survey.scanned(), survey.succeeded()),
                survey.nativeCount(), percent(survey.nativeCount(), survey.succeeded()),
                survey.failed());
        if (survey.failed() > 0) {
            System.out.println("       ※ 비율의 분모는 판별에 성공한 " + survey.succeeded() + "개입니다");
        }
        for (ScanSurvey.ExtStat stat : survey.byExtension()) {
            System.out.printf("       %-5s 총 %d개 · 스캔본 %d · 네이티브 %d · 실패 %d%n",
                    stat.ext().isEmpty() ? "(없음)" : stat.ext(),
                    stat.total(), stat.scanned(), stat.nativeCount(), stat.failed());
        }
        printFailureKinds(survey);
    }

    /**
     * 실패 사유 집계 — {@code --summary}에서도 낸다.
     *
     * <p>"판별 실패 203개"만으로는 손쓸 것이 없다. 갈래별로 나눠야 변환하면 살릴 수 있는 구버전과,
     * 원본을 다시 받아야 하는 암호 문서와, 포기할 손상 파일이 갈린다.
     */
    private void printFailureKinds(ScanSurvey survey) {
        if (survey.failed() == 0) {
            return;
        }
        System.out.printf("%n[실패 사유] 총 %d개%n", survey.failed());
        for (ScanSurvey.FailureStat stat : survey.byFailureKind(FAILURE_SAMPLES)) {
            System.out.printf("       %-18s %4d개  %s%n",
                    stat.kind().name(), stat.count(), stat.kind().description());
            for (Path sample : stat.samples()) {
                System.out.println("                          예) " + sample);
            }
        }
        if (failuresFile == null) {
            System.out.println("       ※ 전건 목록은 --failures <경로> 로 저장할 수 있습니다");
        }
    }

    /** JSON 출력 구조 — summary / by_extension / files(--summary면 생략). */
    private Map<String, Object> toJsonReport(ScanSurvey survey) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", survey.total());
        summary.put("scanned", survey.scanned());
        summary.put("native", survey.nativeCount());
        summary.put("failed", survey.failed());
        summary.put("succeeded", survey.succeeded());
        summary.put("scanned_ratio", Math.round(survey.scannedRatio() * 1000) / 1000.0);

        List<Map<String, Object>> byExtension = new ArrayList<>();
        for (ScanSurvey.ExtStat stat : survey.byExtension()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ext", stat.ext());
            row.put("total", stat.total());
            row.put("scanned", stat.scanned());
            row.put("native", stat.nativeCount());
            row.put("failed", stat.failed());
            byExtension.add(row);
        }

        List<Map<String, Object>> byFailureKind = new ArrayList<>();
        for (ScanSurvey.FailureStat stat : survey.byFailureKind(FAILURE_SAMPLES)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", stat.kind().name());
            row.put("description", stat.kind().description());
            row.put("count", stat.count());
            row.put("samples", stat.samples().stream().map(Path::toString).toList());
            byFailureKind.add(row);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", summary);
        report.put("by_extension", byExtension);
        report.put("by_failure_kind", byFailureKind);
        if (!summaryOnly) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ScanSurvey.Entry entry : survey.entries()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("file", entry.file().toString());
                row.put("ext", entry.ext());
                row.put("status", entry.status().label());
                row.put("is_scanned", entry.scanned());
                if (entry.kind() != null) {
                    row.put("kind", entry.kind().name());
                }
                if (entry.error() != null) {
                    row.put("error", entry.error());
                }
                rows.add(row);
            }
            report.put("files", rows);
        }
        return report;
    }

    /** {@code --failures} 산출물 — 실패 건만 담아 재처리 대상을 그대로 넘길 수 있게 한다. */
    private Map<String, Object> toFailureReport(ScanSurvey survey) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScanSurvey.Entry entry : survey.failures()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", entry.file().toString());
            row.put("ext", entry.ext());
            row.put("kind", entry.kind().name());
            row.put("description", entry.kind().description());
            row.put("error", entry.error());
            rows.add(row);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("total", survey.total());
        report.put("failed", survey.failed());
        report.put("failures", rows);
        return report;
    }

    /** 분모가 0이면 0.0% — 판별 성공 건이 하나도 없을 때 나눗셈을 피한다. */
    private static String percent(int value, int base) {
        return base == 0 ? "0.0%" : String.format("%.1f%%", 100.0 * value / base);
    }
}
