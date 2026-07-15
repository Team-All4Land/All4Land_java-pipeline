package com.onnara.extract.common;

import com.onnara.extract.common.model.NoticeRecord;
import com.onnara.extract.common.model.RawContent;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;
import com.onnara.extract.common.model.SchemaResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * raw JSON(§4) → 표준 스키마(§5) 매핑.
 *
 * <p>흐름: ① 문단 메타(기관·고시번호·고시일·고시자·제목) →
 * ② 문단 라벨:값 → ③ 표(라벨/값 쌍, 헤더형 다건 목록, 셀 내 라벨:값) →
 * ④ 정규화(날짜 ISO, 기간 분리, 주소 폴백). 매핑 안 된 라벨은 extras 보존.
 */
public final class Mapper {

    // "1. 허가연월일 : 2026. 6. 5." / "가. 주  소 : ..." / "- 위  치 : ..."
    private static final Pattern LABEL_VALUE = Pattern.compile(
            "^\\s*(?:[-–—·o○]\\s*)?(?:\\d{1,2}\\s*[.)]|[가-힣]\\s*[.)]|[①-⑳㉮-㉻])?\\s*"
                    + "([^:：]{1,40}?)\\s*[:：]\\s*(.*)$");

    /** extras에 보존할 라벨의 최대 정규화 길이 — 문장 오탐 방지. */
    private static final int MAX_EXTRA_LABEL_LEN = 20;

    /** 인스턴스화 방지 — 정적 매핑 함수만 제공하는 유틸리티 클래스. */
    private Mapper() {
    }

    /** 엔진 식별자 없이 매핑한다(map 서브커맨드 등 엔진을 모르는 경로용). */
    public static SchemaResult mapToSchema(RawDocument raw) {
        return mapToSchema(raw, null);
    }

    /**
     * raw 문서를 표준 스키마로 매핑한다 — 이 클래스의 진입점.
     *
     * <p>문단·표를 분리해 문서 메타와 라벨:값을 추출하고, 헤더형 목록표면 행마다
     * 독립 레코드를 만든다. 마지막에 날짜·기간·주소를 정규화한다.
     *
     * @param engine 산출 스키마의 {@code engine} 필드에 기록할 추출 엔진 식별자(없으면 null)
     */
    public static SchemaResult mapToSchema(RawDocument raw, String engine) {
        List<String> paragraphs = new ArrayList<>();
        List<RawTable> tables = new ArrayList<>();
        for (RawContent item : raw.getContent()) {
            if (item instanceof RawParagraph p && p.getText() != null && !p.getText().isBlank()) {
                paragraphs.add(p.getText().trim());
            } else if (item instanceof RawTable t) {
                tables.add(t);
            }
        }

        NoticeRecord.Builder base = new NoticeRecord.Builder();

        // 문서 전체가 1열 표(테두리 박스) 안에 있는 서식도 있으므로
        // 메타 추출은 문단 + 표 셀 줄을 합친 목록에서 수행한다.
        List<String> metaLines = new ArrayList<>(paragraphs);
        for (RawTable table : tables) {
            if (table.getGrid() == null) {
                continue;
            }
            for (List<String> row : table.getGrid()) {
                for (String cell : row) {
                    if (cell != null && !cell.isBlank()) {
                        for (String line : cell.split("\n")) {
                            if (!line.isBlank()) {
                                metaLines.add(line.trim());
                            }
                        }
                    }
                }
            }
        }

        applyDocumentMeta(base, metaLines, tables);
        applyParagraphLabels(base, paragraphs);

        List<NoticeRecord.Builder> tableRecords = new ArrayList<>();
        for (RawTable table : tables) {
            // 라벨/값 쌍 스캔은 원본 칸 배치를 그대로 써야 한다 - cleanGrid로 완전히
            // 빈 열을 제거하면 병합 셀 때문에 값 칸이 빈 서로 다른 필드들이 옆으로
            // 붙어버려, 값 탐색이 인접 필드의 라벨을 삼키는 문제가 생긴다.
            List<List<String>> rawGrid = table.getGrid() == null ? List.of() : trimGrid(table.getGrid());
            List<List<String>> cleanedGrid = Tables.cleanGrid(rawGrid);
            if (cleanedGrid.isEmpty()) {
                continue;
            }
            List<NoticeRecord.Builder> headerRows = tryHeaderTable(cleanedGrid);
            if (!headerRows.isEmpty()) {
                tableRecords.addAll(headerRows);
            } else {
                applyLabelPairCells(base, rawGrid);
                applyInCellLabels(base, rawGrid);
            }
        }

        normalize(base, paragraphs);

        SchemaResult result = new SchemaResult(
                raw.getSourceFile(), raw.getFileType(), raw.isScanned(), engine);
        if (tableRecords.isEmpty()) {
            result.getRecords().add(base.build());
        } else {
            for (NoticeRecord.Builder row : tableRecords) {
                inheritMeta(row, base);
                normalize(row, List.of());
                result.getRecords().add(row.build());
            }
        }
        result.setImages(new ArrayList<>(raw.getImages()));
        return result;
    }

    // ── ① 문서 메타 ──────────────────────────────────────────────

    /**
     * 문서 단위 메타(기관·고시번호·고시일·고시자·제목)를 추출해 base에 채운다.
     * "OO청 고시 제2026-88호" 문단에서 기관/번호를 분리하고, 그 이후 줄에서
     * 날짜·고시자를 찾으며, 제목은 고시문 표 또는 번호 다음 문단에서 추정한다.
     */
    private static void applyDocumentMeta(NoticeRecord.Builder base,
                                          List<String> paragraphs,
                                          List<RawTable> tables) {
        int noticeNoIdx = -1;
        for (int i = 0; i < paragraphs.size(); i++) {
            Optional<String[]> hit = Heuristics.agencyAndNoticeNo(paragraphs.get(i));
            if (hit.isPresent()) {
                base.set("agency", hit.get()[0]);
                base.set("notice_no", hit.get()[1]);
                noticeNoIdx = i;
                break;
            }
        }
        for (int i = Math.max(0, noticeNoIdx); i < paragraphs.size(); i++) {
            String text = paragraphs.get(i);
            if (!base.has("notice_date") && !LABEL_VALUE.matcher(text).matches()
                    && Dates.toIso(text).isPresent()
                    && text.replaceAll("[\\d\\s.년월일:～~’'-]", "").isEmpty()) {
                base.set("notice_date", text);
            }
            if (!base.has("signer") && Heuristics.looksLikeSigner(text)) {
                base.set("signer", text.trim());
            }
        }

        String title = Heuristics.guessTitleFromTables(tables);
        if (title == null && noticeNoIdx >= 0 && noticeNoIdx + 1 < paragraphs.size()) {
            String next = paragraphs.get(noticeNoIdx + 1);
            if (!LABEL_VALUE.matcher(next).matches()
                    && Dates.toIso(next).isEmpty()
                    && !Heuristics.looksLikeSigner(next)) {
                title = next;
            }
        }
        base.set("title", title);
    }

    // ── ② 문단 라벨:값 ───────────────────────────────────────────

    /**
     * 문단들을 "라벨 : 값" 패턴으로 스캔해 builder에 채운다.
     * 값이 비어 있으면 다음 문단을, 라벨과 ":값"이 줄바꿈으로 갈라진 서식이면
     * 앞 문단을 라벨로 삼아 이어 붙인다.
     */
    private static void applyParagraphLabels(NoticeRecord.Builder builder, List<String> paragraphs) {
        for (int i = 0; i < paragraphs.size(); i++) {
            String text = paragraphs.get(i);

            // 이전 문단이 콜론 없는 라벨이고 이번 문단이 ":값" 연속줄인 경우
            if ((text.startsWith(":") || text.startsWith("：")) && i > 0) {
                String prev = paragraphs.get(i - 1);
                if (!LABEL_VALUE.matcher(prev).matches()) {
                    applyLabel(builder, prev, text.substring(1).trim());
                    continue;
                }
            }

            Matcher m = LABEL_VALUE.matcher(text);
            if (!m.matches()) {
                continue;
            }
            String label = m.group(1);
            String value = m.group(2).trim();
            // 값이 비어 있으면 다음 문단을 값으로 소비
            if (value.isEmpty() && i + 1 < paragraphs.size()
                    && !LABEL_VALUE.matcher(paragraphs.get(i + 1)).matches()) {
                value = paragraphs.get(i + 1).trim();
            }
            applyLabel(builder, label, value);
        }
    }

    /**
     * 라벨을 canonical 필드로 매핑해 값을 세팅한다. 매핑되지 않으면 정규화 라벨을
     * extras에 보존한다(단, 문장 오탐 방지를 위해 {@value #MAX_EXTRA_LABEL_LEN}자 이하만).
     */
    private static void applyLabel(NoticeRecord.Builder builder, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Optional<String> canonical = Synonyms.canonicalFor(label);
        if (canonical.isPresent()) {
            builder.set(canonical.get(), value);
        } else {
            String normalized = Synonyms.normalizeLabel(label);
            if (!normalized.isEmpty() && normalized.length() <= MAX_EXTRA_LABEL_LEN) {
                builder.extra(normalized, value);
            }
        }
    }

    // ── ③ 표 처리 ────────────────────────────────────────────────

    /**
     * 헤더형 목록표: 첫 행 셀의 60% 이상이 canonical 필드로 매핑되면
     * 이후 각 행을 독립 레코드로 변환한다(다건 고시 목록). 아니면 빈 목록.
     */
    private static List<NoticeRecord.Builder> tryHeaderTable(List<List<String>> grid) {
        if (grid.size() < 2 || grid.get(0).size() < 2) {
            return List.of();
        }
        List<String> header = grid.get(0);
        List<Optional<String>> columns = new ArrayList<>(header.size());
        int mapped = 0;
        for (String cell : header) {
            Optional<String> canonical = Synonyms.canonicalFor(cell);
            columns.add(canonical);
            if (canonical.isPresent()) {
                mapped++;
            }
        }
        if (mapped < Math.ceil(header.size() * 0.6)) {
            return List.of();
        }
        List<NoticeRecord.Builder> rows = new ArrayList<>();
        for (int r = 1; r < grid.size(); r++) {
            List<String> row = grid.get(r);
            NoticeRecord.Builder builder = new NoticeRecord.Builder();
            for (int c = 0; c < row.size() && c < columns.size(); c++) {
                String value = row.get(c);
                if (value.isEmpty()) {
                    continue;
                }
                if (columns.get(c).isPresent()) {
                    builder.set(columns.get(c).get(), value);
                } else {
                    builder.extra(Synonyms.normalizeLabel(header.get(c)), value);
                }
            }
            if (!builder.isEmpty()) {
                rows.add(builder);
            }
        }
        return rows;
    }

    /** 격자의 각 셀에서 null→"" 치환과 앞뒤 공백 제거만 수행한다(칸 배치는 보존). */
    private static List<List<String>> trimGrid(List<List<String>> rows) {
        List<List<String>> out = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            List<String> trimmed = new ArrayList<>(row.size());
            for (String cell : row) {
                trimmed.add(cell == null ? "" : cell.trim());
            }
            out.add(trimmed);
        }
        return out;
    }

    /**
     * 라벨/값[/라벨/값] 셀 쌍 스캔 (2열·4열 서식).
     *
     * <p>값은 라벨의 바로 다음 칸(j+1)만 본다 — 여러 칸을 건너뛰며 "다음 비어있지
     * 않은 칸"을 찾으면, 병합 셀로 인해 값 칸이 비어있는 서식에서 옆 필드의 라벨을
     * 값으로 잘못 채간다(예: "관리청"의 값 칸이 빈 채로 "관리번호" 라벨을 삼킴).
     */
    private static void applyLabelPairCells(NoticeRecord.Builder builder, List<List<String>> grid) {
        for (List<String> row : grid) {
            for (int j = 0; j + 1 < row.size(); j++) {
                String labelCell = row.get(j);
                if (labelCell.isEmpty() || labelCell.contains("\n")) {
                    continue;
                }
                Optional<String> canonical = Synonyms.canonicalFor(labelCell);
                if (canonical.isEmpty()) {
                    continue;
                }
                String value = row.get(j + 1);
                if (!value.isEmpty() && Synonyms.canonicalFor(value).isEmpty()) {
                    builder.set(canonical.get(), value);
                }
            }
        }
    }

    /** 셀 내부의 "라벨 : 값" 줄 처리 (1열 고시문 서식 등). */
    private static void applyInCellLabels(NoticeRecord.Builder builder, List<List<String>> grid) {
        for (List<String> row : grid) {
            for (String cell : row) {
                if (cell.isEmpty() || cell.indexOf(':') < 0 && cell.indexOf('：') < 0) {
                    continue;
                }
                List<String> lines = new ArrayList<>(List.of(cell.split("\n")));
                applyParagraphLabels(builder, lines);
            }
        }
    }

    // ── ④ 정규화 ─────────────────────────────────────────────────

    /**
     * 레코드 값을 후처리한다: 고시일·승인일 ISO 변환, 공사기간을 시작/종료로 분리,
     * 신고자 주소가 비면 성명 블록에서 주소를 보충.
     */
    private static void normalize(NoticeRecord.Builder builder, List<String> paragraphs) {
        normalizeDate(builder, "notice_date");
        normalizeDate(builder, "approval_date");

        String period = builder.get(Synonyms.WORK_PERIOD);
        if (period != null) {
            builder.overwrite(Synonyms.WORK_PERIOD, null);
            Optional<String[]> halves = Dates.splitRange(period);
            if (halves.isPresent()) {
                Dates.toIso(halves.get()[0]).ifPresent(v -> builder.set("work_period_start", v));
                Dates.toIso(halves.get()[1]).ifPresent(v -> builder.set("work_period_end", v));
            }
            if (!builder.has("work_period_start") && !builder.has("work_period_end")) {
                builder.extra(Synonyms.WORK_PERIOD, period);
            }
        }

        if (!builder.has("applicant_address")) {
            String name = builder.get("applicant_name");
            if (name != null) {
                Address.extract(name).ifPresent(v -> builder.set("applicant_address", v));
            }
        }
    }

    /** 날짜 필드를 ISO로 변환한다. 변환 불가면 필드를 비우고 원문을 extras로 보존한다. */
    private static void normalizeDate(NoticeRecord.Builder builder, String field) {
        String value = builder.get(field);
        if (value == null) {
            return;
        }
        Optional<String> iso = Dates.toIso(value);
        if (iso.isPresent()) {
            builder.overwrite(field, iso.get());
        } else {
            builder.overwrite(field, null);
            builder.extra(field, value);
        }
    }

    /** 헤더형 표 레코드에 문서 메타(기관·번호·일자·제목·고시자)를 상속. */
    private static void inheritMeta(NoticeRecord.Builder row, NoticeRecord.Builder base) {
        for (String field : List.of("agency", "notice_no", "notice_date", "title", "signer")) {
            String value = base.get(field);
            if (value != null) {
                row.set(field, value);
            }
        }
    }
}
