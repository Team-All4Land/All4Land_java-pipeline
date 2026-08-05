package com.onnara.extract.detect;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 문서 집합 전체의 스캔 판별 집계 — 추출·매핑·OCR 없이 1차 분기({@link ScanDetector})만 돌린 결과다.
 *
 * <p>OCR 서브프로세스를 띄우지 않으므로 코퍼스 전체에 돌려도 비용은 파일 파싱뿐이다.
 * "스캔본이 몇 건인가"를 먼저 확인해 OCR 소요 시간과 {@code ocr.cli.timeout-sec}를
 * 가늠하는 용도로 쓴다.
 *
 * <p>판별 실패(지원하지 않는 확장자, 손상 파일)는 네이티브가 아니라 별도 상태로 센다 —
 * 실패를 네이티브에 합치면 스캔본 비중이 실제보다 낮게 보인다. 그래서 비율의 분모는
 * 전체 건수가 아니라 <b>판별에 성공한 건수</b>({@link #succeeded()})다.
 */
public record ScanSurvey(List<Entry> entries) {

    /** 파일 한 건의 판별 결과. 판별에 실패한 건만 {@code error}가 채워진다. */
    public record Entry(Path file, String ext, Status status, String error) {

        /** 스캔본으로 판정됐는지 — 판별 실패는 false다(스캔본 목록에 넣으면 안 된다). */
        public boolean scanned() {
            return status == Status.SCANNED;
        }
    }

    /** 확장자 하나에 대한 소계. */
    public record ExtStat(String ext, int total, int scanned, int nativeCount, int failed) {
    }

    /** 파일별 판별 결과 상태. */
    public enum Status {

        /** 본문이 이미지 안에만 있어 OCR로 라우팅될 파일. */
        SCANNED,

        /** 네이티브 추출기로 처리될 파일. */
        NATIVE,

        /** 판별 자체가 실패한 파일 — 스캔/네이티브 어느 쪽으로도 세지 않는다. */
        FAILED;

        /** JSON·텍스트 출력에 쓰는 소문자 이름. */
        public String label() {
            return switch (this) {
                case SCANNED -> "scanned";
                case NATIVE -> "native";
                case FAILED -> "failed";
            };
        }
    }

    /**
     * 파일 목록을 순서대로 판별해 집계한다. 파일 단위 실패는 {@link Status#FAILED}로
     * 격리하므로 중간에 멈추지 않는다(배치 격리 규칙과 동일).
     */
    public static ScanSurvey of(List<Path> files) {
        List<Entry> entries = new ArrayList<>();
        for (Path file : files) {
            String ext = DetectorRegistry.extensionOf(file);
            try {
                Status status = DetectorRegistry.isScanned(file) ? Status.SCANNED : Status.NATIVE;
                entries.add(new Entry(file, ext, status, null));
            } catch (Exception e) {
                entries.add(new Entry(file, ext, Status.FAILED, reasonOf(e)));
            }
        }
        return new ScanSurvey(List.copyOf(entries));
    }

    /** 검사한 전체 파일 수(판별 실패 포함). */
    public int total() {
        return entries.size();
    }

    /** 스캔본으로 판정된 파일 수 — OCR 서브프로세스를 타게 될 건수다. */
    public int scanned() {
        return count(Status.SCANNED);
    }

    /** 네이티브로 판정된 파일 수. */
    public int nativeCount() {
        return count(Status.NATIVE);
    }

    /** 판별에 실패한 파일 수. */
    public int failed() {
        return count(Status.FAILED);
    }

    /** 판별에 성공한 파일 수 — 비율 계산의 분모. */
    public int succeeded() {
        return total() - failed();
    }

    /** 판별 성공분 중 스캔본 비율(0.0~1.0). 성공 건이 없으면 0. */
    public double scannedRatio() {
        int base = succeeded();
        return base == 0 ? 0.0 : (double) scanned() / base;
    }

    /** 스캔본으로 판정된 파일 경로 목록(입력 순서 유지). */
    public List<Path> scannedFiles() {
        return entries.stream().filter(Entry::scanned).map(Entry::file).toList();
    }

    /** 확장자별 소계 — 확장자 이름 오름차순(출력 순서를 입력에 의존하지 않게 한다). */
    public List<ExtStat> byExtension() {
        Map<String, Map<Status, Integer>> buckets = new TreeMap<>();
        for (Entry entry : entries) {
            buckets.computeIfAbsent(entry.ext(), k -> new EnumMap<>(Status.class))
                    .merge(entry.status(), 1, Integer::sum);
        }
        List<ExtStat> stats = new ArrayList<>();
        for (Map.Entry<String, Map<Status, Integer>> bucket : buckets.entrySet()) {
            Map<Status, Integer> counts = bucket.getValue();
            int scanned = counts.getOrDefault(Status.SCANNED, 0);
            int nativeCount = counts.getOrDefault(Status.NATIVE, 0);
            int failed = counts.getOrDefault(Status.FAILED, 0);
            stats.add(new ExtStat(bucket.getKey(), scanned + nativeCount + failed,
                    scanned, nativeCount, failed));
        }
        return List.copyOf(stats);
    }

    private int count(Status status) {
        return (int) entries.stream().filter(e -> e.status() == status).count();
    }

    /** 예외 메시지 — 메시지 없는 예외는 타입 이름으로 대신한다. */
    private static String reasonOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
