package com.onnara.extract.common;

import com.onnara.extract.common.model.NoticeRecord;
import com.onnara.extract.common.model.RawContent;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;
import com.onnara.extract.common.model.SchemaResult;
import com.onnara.extract.common.table.InterpretedTable;
import com.onnara.extract.common.table.TableFact;
import com.onnara.extract.common.table.TableInterpreter;
import com.onnara.extract.common.table.TableKind;
import com.onnara.extract.common.table.TableRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * raw JSON(§4) → 표준 스키마(§5) 매핑.
 *
 * <p>흐름: ① 문단 메타(기관·고시번호·고시일·고시자·제목) → ② 문단 라벨:값 →
 * ③ 표 해석 결과 적용 → ④ 정규화(날짜 ISO, 기간 분리, 주소 폴백).
 * 매핑 안 된 라벨은 extras 보존.
 *
 * <p>표를 읽는 규칙 자체는 {@link TableInterpreter}가 담당한다. 이 클래스는 그
 * 해석 결과(어느 칸에서 무슨 라벨을 읽었고 어느 필드로 매핑됐는지)를 레코드에
 * 적용하는 일만 한다 — 표 읽기 오류와 사전 누락을 따로 진단할 수 있도록 분리했다.
 */
public final class Mapper {

    // "제2026-26호(2026. 6. 11.)"처럼 번호 뒤에 괄호로 병기된 일자
    private static final Pattern TRAILING_PAREN = Pattern.compile(
            "[(（]([^()（）]*)[)）]\\s*$");

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
     * @param engine 산출 스키마의 {@code engine} 필드에 기록할 추출 엔진 식별자(없으면 null)
     */
    public static SchemaResult mapToSchema(RawDocument raw, String engine) {
        return mapToSchema(raw, engine, TableInterpreter.interpret(TableInterpreter.tablesOf(raw)));
    }

    /**
     * 이미 해석된 표 결과를 재사용해 매핑한다 — 중간 산출물({@code *.tables.json})을
     * 저장하는 경로에서 같은 표를 두 번 해석하지 않기 위한 진입점.
     *
     * @param interpreted {@link TableInterpreter}가 raw 문서의 표들을 해석한 결과
     */
    public static SchemaResult mapToSchema(RawDocument raw, String engine,
                                           List<InterpretedTable> interpreted) {
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

        applyDocumentMeta(base, metaLines(paragraphs, tables), tables);
        applyParagraphLabels(base, paragraphs);

        List<NoticeRecord.Builder> tableRecords = new ArrayList<>();
        for (InterpretedTable table : interpreted) {
            for (TableRecord record : table.records()) {
                if (table.kind() == TableKind.HEADER_LIST) {
                    NoticeRecord.Builder row = new NoticeRecord.Builder();
                    applyFacts(row, record.fields());
                    tableRecords.add(row);
                } else {
                    applyFacts(base, record.fields());
                }
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

    /**
     * 문서 메타를 찾을 후보 줄 목록 — 문단에 표 셀의 줄들을 이어 붙인다.
     *
     * <p>문서 전체가 1열 표(테두리 박스) 안에 있는 서식도 있어 문단만으로는
     * 기관·고시번호를 못 찾는다. 병합 셀은 같은 내용이 옆 칸으로 반복되므로
     * 연속 중복 줄은 걸러낸다.
     */
    private static List<String> metaLines(List<String> paragraphs, List<RawTable> tables) {
        List<String> lines = new ArrayList<>(paragraphs);
        for (RawTable table : tables) {
            if (table.getGrid() == null) {
                continue;
            }
            for (List<String> row : table.getGrid()) {
                for (String cell : dedupeConsecutive(trimRow(row))) {
                    if (cell.isEmpty()) {
                        continue;
                    }
                    for (String line : cell.split("\n")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()
                                && (lines.isEmpty() || !lines.get(lines.size() - 1).equals(trimmed))) {
                            lines.add(trimmed);
                        }
                    }
                }
            }
        }
        return lines;
    }

    /** 표 해석 결과의 라벨:값들을 레코드에 반영한다(매핑된 것은 필드로, 나머지는 extras로). */
    private static void applyFacts(NoticeRecord.Builder builder, List<TableFact> facts) {
        for (TableFact fact : facts) {
            if (fact.canonical() != null) {
                builder.set(fact.canonical(), fact.value());
            } else {
                builder.extra(fact.label(), fact.value());
            }
        }
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
            if (!base.has("notice_date") && !Labels.isLabelLine(text)
                    && Dates.toIso(text).isPresent()
                    && text.replaceAll("[\\d\\s.년월일:～~’'-]", "").isEmpty()) {
                base.set("notice_date", text);
            }
            if (!base.has("signer") && Heuristics.looksLikeSigner(text)) {
                base.set("signer", Heuristics.cleanSigner(text));
            }
        }

        String title = Heuristics.guessTitleFromTables(tables);
        if (title == null && noticeNoIdx >= 0 && noticeNoIdx + 1 < paragraphs.size()) {
            String next = paragraphs.get(noticeNoIdx + 1);
            if (!Labels.isLabelLine(next)
                    && Dates.toIso(next).isEmpty()
                    && !Heuristics.looksLikeSigner(next)) {
                title = Heuristics.collapseSpacedText(next);
            }
        }
        base.set("title", title);
    }

    // ── ② 문단 라벨:값 ───────────────────────────────────────────

    /** 문단들을 "라벨 : 값" 패턴으로 스캔해 builder에 채운다. */
    private static void applyParagraphLabels(NoticeRecord.Builder builder, List<String> paragraphs) {
        for (Labels.Pair pair : Labels.scan(paragraphs)) {
            applyLabel(builder, pair.label(), pair.value());
        }
    }

    /**
     * 라벨을 canonical 필드로 매핑해 값을 세팅한다. 매핑되지 않으면 정규화 라벨을
     * extras에 보존한다({@link Labels#acceptableExtra} 오탐 가드 적용).
     */
    private static void applyLabel(NoticeRecord.Builder builder, String label, String value) {
        Optional<String> canonical = Synonyms.canonicalFor(label);
        if (canonical.isPresent()) {
            builder.set(canonical.get(), value);
            return;
        }
        String normalized = Synonyms.normalizeLabel(label);
        if (Labels.acceptableExtra(normalized, value)) {
            builder.extra(normalized, value);
        }
    }

    // ── ③ 표 격자 유틸 ───────────────────────────────────────────

    /** 행의 각 셀에서 null→"" 치환과 앞뒤 공백 제거를 수행한다. */
    private static List<String> trimRow(List<String> row) {
        List<String> trimmed = new ArrayList<>(row.size());
        for (String cell : row) {
            trimmed.add(cell == null ? "" : cell.trim());
        }
        return trimmed;
    }

    /**
     * 연속 중복 셀을 하나로 줄인다 — 병합 셀(col_span)이 같은 내용을 옆 칸으로
     * 반복 복제한 격자에서 메타 줄을 모을 때 쓴다. 빈 칸은 값 자리 구분자
     * 역할을 하므로 그대로 남긴다(연속 빈 칸만 하나로).
     */
    private static List<String> dedupeConsecutive(List<String> row) {
        List<String> out = new ArrayList<>(row.size());
        for (String cell : row) {
            if (out.isEmpty() || !out.get(out.size() - 1).equals(cell)) {
                out.add(cell);
            }
        }
        return out;
    }

    // ── ④ 정규화 ─────────────────────────────────────────────────

    /**
     * 레코드 값을 후처리한다: 고시일·승인일 ISO 변환, 공사기간을 시작/종료로 분리,
     * 신고자 주소가 비면 성명 블록에서 주소를 보충.
     */
    private static void normalize(NoticeRecord.Builder builder, List<String> paragraphs) {
        splitApprovalNoDate(builder);
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

    /**
     * "승인번호(연월일)" 열/라벨 서식은 값에 번호와 일자가 같이 온다
     * (예: "제2026-26호(2026. 6. 11.)"). 끝의 괄호가 날짜로 파싱되면
     * approval_no에서 떼어 approval_date로 옮긴다.
     */
    private static void splitApprovalNoDate(NoticeRecord.Builder builder) {
        String approvalNo = builder.get("approval_no");
        if (approvalNo == null) {
            return;
        }
        Matcher m = TRAILING_PAREN.matcher(approvalNo);
        if (!m.find() || Dates.toIso(m.group(1)).isEmpty()) {
            return;
        }
        String number = approvalNo.substring(0, m.start()).trim();
        if (number.isEmpty()) {
            return;
        }
        builder.set("approval_date", m.group(1).trim());
        builder.overwrite("approval_no", number);
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
