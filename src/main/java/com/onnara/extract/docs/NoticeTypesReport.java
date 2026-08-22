package com.onnara.extract.docs;

import com.onnara.extract.common.Json;
import com.onnara.extract.common.NoticeTypes;
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

/**
 * 미분류 제목 검토 리포트 — 공고종류 레지스트리 보강의 근거 자료를 Markdown으로 낸다.
 *
 * <p>{@code NOTI_KND_CD}가 비는 이유는 둘이고, 손볼 곳이 서로 다르다. 제목을 아예 못 읽었으면
 * 추출·휴리스틱 문제이고, 제목은 멀쩡한데 못 가렸으면 레지스트리에 자리가 없는 것이다.
 * 이 리포트는 <b>뒤쪽만</b> 표로 세운다 — 앞쪽은 건수만 머리말에 적어 두 문제가 섞이지 않게 한다.
 *
 * <p>왜 자동 등록이 아니라 리포트인가: 제목에서 코드를 만들어 넣으면 오타와 서식 변형이 각각
 * 새 종류가 되어, 종류를 축으로 삼는 집계가 통째로 무너진다. 실제로 입찰공고 11건이 이 방식으로
 * 드러나 {@code ETC_BID_NOTI} 한 줄로 정리됐다. 사람이 한 줄 추가하면
 * {@link com.onnara.extract.db.ReferenceSync}가 {@code NOTI_KND_TC}까지 알아서 따라온다.
 *
 * <p>{@link ExtrasReport}(미매핑 라벨)와 같은 절차를 공고종류에 적용한 것이다.
 */
public final class NoticeTypesReport {

    /** 레지스트리 보강 후보로 우선 검토할 최소 반복 건수. */
    private static final int REPEAT_THRESHOLD = 3;

    /** 정규화 제목 하나의 집계 결과. */
    private record Entry(String normalized, int count, String example, Set<String> documents) {
    }

    /** 인스턴스화 방지 — 정적 렌더링 함수만 제공하는 유틸리티 클래스. */
    private NoticeTypesReport() {
    }

    /**
     * 스키마 JSON들을 읽어 미분류 제목 리포트를 렌더링한다.
     *
     * @param schemaFiles {@code *.schema.json} 경로 목록
     */
    public static String render(List<Path> schemaFiles) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> examples = new LinkedHashMap<>();
        Map<String, Set<String>> documents = new LinkedHashMap<>();
        Map<String, Integer> classified = new LinkedHashMap<>();

        int readFiles = 0;
        int titleless = 0;
        for (Path file : schemaFiles) {
            SchemaResult schema = Json.MAPPER.readValue(file.toFile(), SchemaResult.class);
            readFiles++;
            if (schema.getNotiKndCd() != null) {
                classified.merge(schema.getNotiKndCd(), 1, Integer::sum);
                continue;
            }
            String title = titleOf(schema);
            if (title == null) {
                titleless++;
                continue;
            }
            String normalized = NoticeTypes.normalizeTitle(title);
            counts.merge(normalized, 1, Integer::sum);
            examples.putIfAbsent(normalized, title);
            documents.computeIfAbsent(normalized, k -> new LinkedHashSet<>())
                    .add(schema.getAtchFileNm());
        }

        List<Entry> entries = new ArrayList<>();
        counts.forEach((normalized, count) -> entries.add(new Entry(
                normalized, count, examples.get(normalized), documents.get(normalized))));
        entries.sort(Comparator.comparingInt(Entry::count).reversed()
                .thenComparing(Entry::normalized));

        int unclassified = counts.values().stream().mapToInt(Integer::intValue).sum();

        StringBuilder md = new StringBuilder();
        md.append("# 미분류 제목 검토 리포트\n\n");
        md.append("> **자동 생성 문서입니다.** `java -jar extract.jar dict --review <스키마폴더>`로 갱신합니다.\n")
                .append("> 제목은 읽었는데 공고종류로 가리지 못한 첨부를 전량 집계한 결과입니다.\n\n");

        md.append("| 항목 | 값 |\n|---|---|\n");
        md.append("| 검토 대상 문서 | ").append(readFiles).append("개 |\n");
        md.append("| 종류가 가려진 첨부 | ")
                .append(classified.values().stream().mapToInt(Integer::intValue).sum())
                .append("건 |\n");
        md.append("| 제목은 있으나 미분류 | ").append(unclassified).append("건 |\n");
        md.append("| 제목 자체가 없음 | ").append(titleless)
                .append("건 (레지스트리가 아니라 추출·휴리스틱 문제입니다) |\n");
        md.append("| 레지스트리 | ").append(NoticeTypes.all().size()).append("종 / 버전 `")
                .append(NoticeTypes.version()).append("` |\n\n");

        md.append(unclassifiedTable(entries));
        md.append(classifiedTable(classified));
        md.append(procedure());
        return md.toString();
    }

    /** 미분류 제목을 빈도순으로 나열한다 — 위쪽이 레지스트리 보강 우선순위다. */
    private static String unclassifiedTable(List<Entry> entries) {
        StringBuilder md = new StringBuilder("## 미분류 제목 (빈도순)\n\n");
        if (entries.isEmpty()) {
            return md.append("미분류 제목이 없습니다 — 제목이 있는 첨부는 모두 종류가 가려졌습니다.\n\n")
                    .toString();
        }
        md.append("| # | 정규화 제목 | 건수 | 원문 예시 | 나온 문서 |\n");
        md.append("|---:|---|---:|---|---|\n");
        int i = 1;
        for (Entry e : entries) {
            md.append("| ").append(i++)
                    .append(" | `").append(Markdown.cell(e.normalized())).append('`')
                    .append(" | ").append(e.count())
                    .append(" | ").append(Markdown.cell(e.example()))
                    .append(" | ").append(Markdown.cell(documentList(e.documents())))
                    .append(" |\n");
        }
        long candidates = entries.stream().filter(e -> e.count() >= REPEAT_THRESHOLD).count();
        md.append('\n');
        if (candidates > 0) {
            md.append(REPEAT_THRESHOLD).append("건 이상 반복된 제목이 **").append(candidates)
                    .append("종** 있습니다 — 한 번 나온 제목은 대개 고시문이 아닌 첨부(설계서·현장사진·별지 표지)라,\n")
                    .append("반복되는 것부터 보는 편이 종류를 새로 낼지 판단하기 좋습니다.\n\n");
        } else {
            md.append("반복된 제목은 아직 없습니다. 한 번씩만 나온 제목은 대개 고시문이 아닌 첨부입니다.\n\n");
        }
        return md.toString();
    }

    /** 이미 가려진 종류의 분포 — 새 종류가 기존 종류를 가로챘는지 확인하는 대조군이다. */
    private static String classifiedTable(Map<String, Integer> classified) {
        StringBuilder md = new StringBuilder("## 가려진 공고종류 분포\n\n");
        if (classified.isEmpty()) {
            return md.append("종류가 가려진 첨부가 없습니다.\n\n").toString();
        }
        md.append("| 공고종류 | 종류명 | 상위분류 | 건수 |\n|---|---|---|---:|\n");
        classified.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> md.append("| `").append(e.getKey()).append('`')
                        .append(" | ").append(Markdown.cell(nameOf(e.getKey())))
                        .append(" | ").append(Markdown.cell(familyOf(e.getKey())))
                        .append(" | ").append(e.getValue()).append(" |\n"));
        return md.append('\n').toString();
    }

    /** 종류를 보태는 절차 — 리포트를 보고 무엇을 할지가 문서 안에 있어야 한다. */
    private static String procedure() {
        return """
                ## 종류를 보태는 절차

                1. 위 표에서 반복되는 제목이 실제로 새 공고종류인지 확인합니다 — 원문 예시와 파일을 봅니다.
                2. `src/main/resources/notice_types.json`의 `types`에 한 줄 추가합니다.
                   `priority`는 낮게 잡습니다. 기존 규칙보다 먼저 걸리면 멀쩡히 가려지던 문서를 가로챕니다.
                3. `mvn test`로 기존 분류가 바뀌지 않았는지 확인합니다.
                4. 파이프라인을 다시 돌리면 `ReferenceSync`가 `NOTI_KND_TC`까지 반영합니다 —
                   마이그레이션은 필요 없습니다.

                **자동 등록은 하지 않습니다.** 제목에서 코드를 만들어 넣으면 오타와 서식 변형이 각각
                새 종류가 되어, 종류를 축으로 삼는 집계가 조용히 무너집니다.
                """;
    }

    /** 첫 레코드의 제목 — 비어 있으면 null. */
    private static String titleOf(SchemaResult schema) {
        List<NoticeRecord> records = schema.getRecords();
        if (records == null || records.isEmpty()) {
            return null;
        }
        String title = records.get(0).notiTtl();
        return title == null || title.isBlank() ? null : title.trim();
    }

    /** 문서 목록을 "첫 문서 외 N건" 형태로 줄인다. */
    private static String documentList(Set<String> documents) {
        List<String> names = new ArrayList<>(documents);
        String first = names.get(0);
        return names.size() == 1 ? first : first + " 외 " + (names.size() - 1) + "건";
    }

    /** 종류코드의 사람이 읽는 이름. */
    private static String nameOf(String code) {
        return NoticeTypes.byCode(code).map(NoticeTypes.Spec::notiKndNm).orElse(code);
    }

    /** 종류코드의 상위 묶음. */
    private static String familyOf(String code) {
        return NoticeTypes.byCode(code).map(NoticeTypes.Spec::upperKndNm).orElse("");
    }
}
