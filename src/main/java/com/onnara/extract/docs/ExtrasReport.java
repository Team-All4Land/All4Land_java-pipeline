package com.onnara.extract.docs;

import com.onnara.extract.common.Json;
import com.onnara.extract.common.Synonyms;
import com.onnara.extract.common.model.NoticeRecord;
import com.onnara.extract.common.model.SchemaResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 미매핑 라벨 검토 리포트 — 사전 보강(§9)의 근거 자료를 Markdown으로 낸다.
 *
 * <p>표준 스키마 결과({@code *.schema.json})의 extras를 전량 집계한다. 표본을
 * 눈으로 훑는 대신 전체를 세는 이유는, 라벨 사용 습관이 지자체 단위로 몰려 다녀
 * 일부만 보면 특정 기관의 표기가 통째로 빠지기 때문이다.
 *
 * <p>표준 필드 채움률도 함께 낸다 — 어떤 필드가 자주 비는지가 곧 다음에 손볼
 * 라벨·서식이 무엇인지를 가리킨다.
 */
public final class ExtrasReport {

    /**
     * 채움률 표의 행 순서 — 사전 선언 순서를 그대로 따른다.
     *
     * <p>예전에는 목록을 여기 손으로 박아 뒀는데, 사전이 14필드에서 45필드로 늘 때
     * 그 목록이 조용히 낡아 새 표준항목이 리포트에서 통째로 빠진다. 사전을 유일한
     * 정의처로 두면 항목을 추가해도 리포트가 저절로 따라온다.
     *
     * <p>기간은 가상 필드라 사전의 {@code work_period} 자리에 분리된 두 컬럼을 대신 넣는다.
     */
    private static final List<String> STANDARD_FIELDS = Synonyms.fields().stream()
            .flatMap(f -> f.virtual()
                    ? Stream.of(Synonyms.WORK_PERIOD_START, Synonyms.WORK_PERIOD_END)
                    : Stream.of(f.itemCd()))
            .toList();

    /** 사전 보강 후보로 우선 검토할 최소 반복 건수. */
    private static final int REPEAT_THRESHOLD = 3;

    /** 한 라벨의 집계 결과 — 몇 건, 몇 개 문서, 어떤 값이었는지. */
    private record Entry(String label, int count, Set<String> documents, String example) {
    }

    /** 인스턴스화 방지 — 정적 렌더링 함수만 제공하는 유틸리티 클래스. */
    private ExtrasReport() {
    }

    /**
     * 스키마 JSON들을 읽어 검토 리포트를 렌더링한다.
     *
     * @param schemaFiles {@code *.schema.json} 경로 목록
     */
    public static String render(List<Path> schemaFiles) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Set<String>> documents = new LinkedHashMap<>();
        Map<String, String> examples = new LinkedHashMap<>();
        Map<String, Integer> filled = new LinkedHashMap<>();

        int recordCount = 0;
        int readFiles = 0;
        for (Path file : schemaFiles) {
            SchemaResult schema = Json.MAPPER.readValue(file.toFile(), SchemaResult.class);
            readFiles++;
            for (NoticeRecord record : schema.getRecords()) {
                recordCount++;
                countFilledFields(record, filled);
                if (record.extras() == null) {
                    continue;
                }
                record.extras().forEach((label, value) -> {
                    counts.merge(label, 1, Integer::sum);
                    documents.computeIfAbsent(label, k -> new LinkedHashSet<>())
                            .add(schema.getAtchFileNm());
                    examples.putIfAbsent(label, value);
                });
            }
        }

        List<Entry> entries = new ArrayList<>();
        counts.forEach((label, count) -> entries.add(new Entry(
                label, count, documents.get(label), examples.get(label))));
        entries.sort(Comparator.comparingInt(Entry::count).reversed()
                .thenComparing(Entry::label));

        StringBuilder md = new StringBuilder();
        md.append("# 미매핑 라벨 검토 리포트\n\n");
        md.append("> **자동 생성 문서입니다.** `java -jar extract.jar dict --review <스키마폴더>`로 갱신합니다.\n")
                .append("> 표준 필드로 매핑되지 못해 `extras`에 보존된 라벨을 전량 집계한 결과입니다.\n\n");

        md.append("| 항목 | 값 |\n|---|---|\n");
        md.append("| 검토 대상 문서 | ").append(readFiles).append("개 |\n");
        md.append("| 추출된 레코드 | ").append(recordCount).append("건 |\n");
        md.append("| 미매핑 라벨 | ").append(entries.size()).append("종 |\n");
        md.append("| 미매핑 값 | ").append(counts.values().stream().mapToInt(Integer::intValue).sum())
                .append("건 |\n");
        md.append("| 사전 버전 | `").append(Synonyms.version()).append("` |\n\n");

        md.append(unmappedTable(entries));
        md.append(coverageTable(filled, recordCount));
        md.append(criteria());
        return md.toString();
    }

    /** 미매핑 라벨을 빈도순으로 나열한다 — 위쪽이 사전 보강 우선순위다. */
    private static String unmappedTable(List<Entry> entries) {
        StringBuilder md = new StringBuilder("## 미매핑 라벨 (빈도순)\n\n");
        if (entries.isEmpty()) {
            return md.append("미매핑 라벨이 없습니다 — 모든 라벨이 표준 필드로 매핑됐습니다.\n\n").toString();
        }
        md.append("| # | 라벨 | 건수 | 문서 수 | 예시 값 | 나온 문서 |\n");
        md.append("|---:|---|---:|---:|---|---|\n");
        int i = 1;
        for (Entry e : entries) {
            md.append("| ").append(i++)
                    .append(" | `").append(Markdown.cell(e.label())).append('`')
                    .append(" | ").append(e.count())
                    .append(" | ").append(e.documents().size())
                    .append(" | ").append(Markdown.cell(e.example()))
                    .append(" | ").append(Markdown.cell(documentList(e.documents())))
                    .append(" |\n");
        }
        long candidates = entries.stream().filter(e -> e.count() >= REPEAT_THRESHOLD).count();
        md.append('\n');
        if (candidates > 0) {
            md.append(REPEAT_THRESHOLD).append("건 이상 반복된 라벨이 **").append(candidates)
                    .append("종** 있습니다 — 이들부터 사전 보강을 검토하세요.\n\n");
        } else {
            md.append("아직 ").append(REPEAT_THRESHOLD)
                    .append("건 이상 반복된 라벨은 없습니다. 표본이 적을 수 있으니, ")
                    .append("문서를 더 처리한 뒤 다시 집계하세요.\n\n");
        }
        return md.toString();
    }

    /** 문서 목록을 "첫 문서 외 N건" 형태로 줄인다. */
    private static String documentList(Set<String> documents) {
        List<String> names = new ArrayList<>(documents);
        String first = names.get(0);
        return names.size() == 1 ? first : first + " 외 " + (names.size() - 1) + "건";
    }

    /** 표준 필드별 채움률 — 자주 비는 필드가 다음에 손볼 서식을 가리킨다. */
    private static String coverageTable(Map<String, Integer> filled, int recordCount) {
        StringBuilder md = new StringBuilder("## 표준 필드 채움률\n\n");
        if (recordCount == 0) {
            return md.append("집계할 레코드가 없습니다.\n\n").toString();
        }
        md.append("| 표준 필드 | 표시명 | 채워진 레코드 | 채움률 |\n");
        md.append("|---|---|---:|---:|\n");
        for (String field : STANDARD_FIELDS) {
            int count = filled.getOrDefault(field, 0);
            md.append("| `").append(field).append('`')
                    .append(" | ").append(Markdown.cell(displayFor(field)))
                    .append(" | ").append(count).append(" / ").append(recordCount)
                    .append(" | ").append(String.format("%.0f%%", 100.0 * count / recordCount))
                    .append(" |\n");
        }
        md.append("\n");
        return md.toString();
    }

    /**
     * 필드의 사람이 읽는 표시명 — 기간 컬럼은 가상 필드(work_period)에서 갈라져 나오므로
     * 사전에 그 이름이 없다. 접미사를 떼어 원 필드의 표시명에 방향을 덧붙인다.
     */
    private static String displayFor(String field) {
        return Synonyms.field(field).map(Synonyms.FieldSpec::itemNm).orElseGet(() -> {
            if (field.startsWith("WORK_PERIOD")) {
                String suffix = field.endsWith("_start") ? " (시작)" : " (종료)";
                return Synonyms.field(Synonyms.WORK_PERIOD)
                        .map(Synonyms.FieldSpec::itemNm).orElse(field) + suffix;
            }
            return field;
        });
    }

    /** 레코드에서 값이 채워진 표준 필드를 센다. */
    private static void countFilledFields(NoticeRecord record, Map<String, Integer> filled) {
        fieldValues(record).forEach((field, value) -> {
            if (value != null && !value.isBlank()) {
                filled.merge(field, 1, Integer::sum);
            }
        });
    }

    /** 레코드의 표준 필드 값을 리포트 행 순서대로 펼친다. */
    private static Map<String, String> fieldValues(NoticeRecord r) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : STANDARD_FIELDS) {
            values.put(field, r.get(field));
        }
        return values;
    }

    /** 검토 판단 기준 — 리포트를 보고 무엇을 결정하면 되는지. */
    private static String criteria() {
        return """
                ## 판단 기준

                | 상황 | 조치 |
                |---|---|
                | 기존 표준 필드와 같은 뜻인 라벨이 반복 등장 | 해당 필드의 `synonyms`에 추가 (예: `공시일자` → `notice_date`) |
                | 여러 기관에서 반복되는데 기존 필드에 안 맞음 | 새 표준 컬럼 승격 검토 — 스키마 마이그레이션 필요 |
                | 한두 건뿐인 라벨 | `extras`에 두고 다음 검토 때 다시 본다 |
                | 값이 문장 조각이거나 라벨이 깨져 보임 | 표 읽기 문제일 수 있음 — 해당 문서의 `*.tables.json`에서 원본 좌표를 확인 |

                라벨을 추가한 뒤 파이프라인을 다시 돌리면, 파일 단위 멱등 적재라
                기존 문서도 재적재되어 `extras`에 있던 값이 표준 컬럼으로 승격된다.
                """;
    }
}
