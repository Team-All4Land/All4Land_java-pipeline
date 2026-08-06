package com.onnara.extract.detect;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * 파일 한 건의 판별 결과. 판별에 실패한 건만 {@code kind}·{@code error}가 채워진다.
     *
     * @param ext   판별된 <b>실제 형식</b> 키({@link DocFormat#routingKey}) — 확장자가 아니다.
     *              확장자가 어긋난 파일이 소계를 오염시키지 않게 하려는 것이다
     * @param kind  실패의 갈래 — 수백 건을 대응 단위로 묶는 축({@link FailureClassifier})
     * @param error 원인 체인까지 담은 사유 문장({@link com.onnara.extract.common.Errors#describe})
     */
    public record Entry(Path file, String ext, Status status, FailureKind kind, String error) {

        /** 스캔본으로 판정됐는지 — 판별 실패는 false다(스캔본 목록에 넣으면 안 된다). */
        public boolean scanned() {
            return status == Status.SCANNED;
        }
    }

    /** 확장자 하나에 대한 소계. */
    public record ExtStat(String ext, int total, int scanned, int nativeCount, int failed) {
    }

    /** 실패 갈래 하나에 대한 소계 — 예시 파일을 함께 들어 사유를 곧바로 확인할 수 있게 한다. */
    public record FailureStat(FailureKind kind, int count, List<Path> samples) {
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
     *
     * <p>{@code Exception}이 아니라 {@code Throwable}을 잡는다 — 파일 하나가 힙을 다 먹어
     * {@code OutOfMemoryError}가 나면 그 한 건만 실패로 세고 나머지 수만 건은 계속 돌아야 한다.
     * {@code Exception}만 잡으면 코퍼스 전체 판별이 통째로 중단된다.
     */
    public static ScanSurvey of(List<Path> files) {
        List<Entry> entries = new ArrayList<>();
        for (Path file : files) {
            // 집계 축은 확장자가 아니라 판별된 실제 형식이다 — .hwp로 저장된 HWPX가
            // hwp 소계에 섞이면 "hwp 실패가 많다"는 잘못된 신호를 준다
            String format = DocFormat.routingKey(file);
            try {
                Status status = DetectorRegistry.isScanned(file) ? Status.SCANNED : Status.NATIVE;
                entries.add(new Entry(file, format, status, null, null));
            } catch (Throwable t) {
                FailureClassifier.Result reason = FailureClassifier.classify(file, t);
                entries.add(new Entry(file, format, Status.FAILED, reason.kind(), reason.detail()));
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

    /**
     * 실패 갈래별 소계 — 건수 내림차순(같으면 갈래 선언 순서).
     *
     * <p>실패를 한 덩어리로 세면 "구버전이라 변환하면 살릴 수 있는 것"과 "암호가 걸려 원본이
     * 필요한 것"과 "깨져서 포기할 것"이 구분되지 않는다. 대응이 갈리는 축으로 나눠 센다.
     *
     * @param sampleLimit 갈래마다 함께 들 예시 파일 수(0이면 예시 없음)
     */
    public List<FailureStat> byFailureKind(int sampleLimit) {
        Map<FailureKind, List<Path>> buckets = new EnumMap<>(FailureKind.class);
        for (Entry entry : entries) {
            if (entry.status() == Status.FAILED) {
                buckets.computeIfAbsent(entry.kind() == null ? FailureKind.OTHER : entry.kind(),
                        k -> new ArrayList<>()).add(entry.file());
            }
        }
        List<FailureStat> stats = new ArrayList<>();
        for (Map.Entry<FailureKind, List<Path>> bucket : buckets.entrySet()) {
            List<Path> files = bucket.getValue();
            stats.add(new FailureStat(bucket.getKey(), files.size(),
                    List.copyOf(files.subList(0, Math.min(sampleLimit, files.size())))));
        }
        stats.sort(Comparator.comparingInt(FailureStat::count).reversed()
                .thenComparing(s -> s.kind().ordinal()));
        return List.copyOf(stats);
    }

    /** 판별에 실패한 항목만 입력 순서대로 반환한다(실패 리포트 저장용). */
    public List<Entry> failures() {
        return entries.stream().filter(e -> e.status() == Status.FAILED).toList();
    }

    private int count(Status status) {
        return (int) entries.stream().filter(e -> e.status() == status).count();
    }
}
