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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * raw JSON(§4) → 표준 스키마(§5) 매핑.
 *
 * <p>흐름: ① 문단 메타(기관·고시번호·고시일·고시자·제목) → ② 문단 라벨:값
 * (고시 블록이 반복되는 서식이면 처분별로 가름) → ③ 표 해석 결과 적용 →
 * ④ 정규화(날짜 ISO, 기간 분리, 주소 폴백). 매핑 안 된 라벨은 extras 보존.
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

        List<String> bodyLines = metaLines(paragraphs, tables);
        applyDocumentMeta(base, bodyLines, tables, mappedFields(interpreted));
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
        // 목록표가 없을 때만 본문 블록을 본다 — 표가 있으면 표가 처분의 단위다
        List<NoticeRecord.Builder> records =
                tableRecords.isEmpty() ? dispositionBlocks(bodyLines) : tableRecords;

        normalize(base, paragraphs);

        SchemaResult result = new SchemaResult(
                raw.getFileNm(), raw.getFileExtn(), raw.isScanYn(), engine);
        result.setRealExtn(raw.getRealExtn());
        // 적재 키는 여기서 한 번만 확정한다 — 폴백 순번이 호출마다 새로 발급되므로,
        // 적재 시점에 다시 파싱하면 pipeline 경로와 map→load 경로가 다른 게시물로 갈린다
        SourceFileName.Parsed key = SourceFileName.parse(raw.getFileNm());
        result.setNotiSn(key.notiSn());
        result.setAtchSn(key.atchSn());
        if (records.isEmpty()) {
            result.getRecords().add(base.build());
        } else {
            for (NoticeRecord.Builder row : records) {
                inheritMeta(row, base);
                normalize(row, List.of());
                result.getRecords().add(row.build());
            }
        }
        // 공고종류도 여기서 확정한다 — 순번과 같은 이유로, 적재 때 다시 분류하면
        // pipeline 경로와 map→load 경로가 다른 종류를 받을 수 있다
        result.setNotiKndCd(NoticeTypes.classify(base.get("NOTI_TTL")).orElse(null));
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
                for (String cell : Tables.dedupeConsecutive(row)) {
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

    /**
     * 표 해석이 <b>사전으로 매핑해 낸</b> 표준 필드 이름들 — 추측성 휴리스틱이 양보해야 할 목록이다.
     *
     * <p>표에 {@code 제목:} 라벨이 또렷이 있는데도 "고시번호 다음 줄"이라는 위치 추측이
     * 먼저 채워 버리면, 사전을 갖춰 둔 의미가 없어진다. 값이 아니라 <b>이름만</b> 모으는 이유는
     * 순서를 바꾸지 않기 위해서다 — 실제 값 반영은 지금처럼 뒤에서 하고, 여기서는
     * "이 필드는 표가 책임진다"는 사실만 앞당겨 알려 준다.
     */
    private static Set<String> mappedFields(List<InterpretedTable> interpreted) {
        Set<String> fields = new HashSet<>();
        for (InterpretedTable table : interpreted) {
            // 다건 목록 표는 행마다 레코드가 갈라져 문서 단위 메타가 되지 않는다
            if (table.kind() == TableKind.HEADER_LIST) {
                continue;
            }
            for (TableRecord record : table.records()) {
                for (TableFact fact : record.fields()) {
                    if (fact.itemCd() != null) {
                        fields.add(fact.itemCd());
                    }
                }
            }
        }
        return fields;
    }

    /** 표 해석 결과의 라벨:값들을 레코드에 반영한다(매핑된 것은 필드로, 나머지는 extras로). */
    private static void applyFacts(NoticeRecord.Builder builder, List<TableFact> facts) {
        for (TableFact fact : facts) {
            if (fact.itemCd() != null) {
                builder.set(fact.itemCd(), fact.value());
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
     *
     * @param mappedByTables 표 해석이 사전으로 매핑해 낸 표준 필드 이름들 — 여기 든 필드는
     *                       추측하지 않고 표에 맡긴다
     */
    private static void applyDocumentMeta(NoticeRecord.Builder base,
                                          List<String> paragraphs,
                                          List<RawTable> tables,
                                          Set<String> mappedByTables) {
        int noticeNoIdx = -1;
        for (int i = 0; i < paragraphs.size(); i++) {
            // 앞 줄까지 넘기는 이유: "고 시 문" 머리글 아래에 고시·공고 낱말 없이
            // "평택지방해양수산청 제2018 - 2호"만 오는 서식이 있다
            Optional<String[]> hit = Heuristics.agencyAndNoticeNo(
                    i > 0 ? paragraphs.get(i - 1) : null, paragraphs.get(i));
            if (hit.isPresent()) {
                base.set("BODY_AGNCY_NM", hit.get()[0]);
                base.set("NOTI_NO", hit.get()[1]);
                noticeNoIdx = i;
                break;
            }
        }
        for (int i = Math.max(0, noticeNoIdx); i < paragraphs.size(); i++) {
            String text = paragraphs.get(i);
            if (!base.has("NOTI_YMD") && !Labels.isLabelLine(text)
                    && Dates.toIso(text).isPresent()
                    && text.replaceAll("[\\d\\s.년월일:～~’'-]", "").isEmpty()) {
                base.set("NOTI_YMD", text);
            }
            if (!base.has("NOTI_PSN") && Heuristics.looksLikeSigner(text)) {
                base.set("NOTI_PSN", Heuristics.cleanSigner(text));
            }
        }

        // 표가 제목 라벨을 갖고 있으면 추측하지 않는다. "고시번호 다음 줄"은 어디까지나 위치
        // 추측이라, 그 자리에 다른 라벨이 오는 서식(예: 신문공고 게재 언론기관)에서 엉뚱한 값을
        // 제목으로 굳혀 버린다. set()이 선착순이라 여기서 채우면 표 값이 들어올 자리가 없어진다.
        if (mappedByTables.contains("NOTI_TTL")) {
            return;
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
        base.set("NOTI_TTL", title);
    }

    // ── ② 문단 라벨:값 ───────────────────────────────────────────

    /** 문단들을 "라벨 : 값" 패턴으로 스캔해 builder에 채운다. */
    private static void applyParagraphLabels(NoticeRecord.Builder builder, List<String> paragraphs) {
        for (Labels.Pair pair : Labels.scan(paragraphs)) {
            applyLabel(builder, pair.label(), pair.value());
        }
    }

    /**
     * 라벨을 itemCd 필드로 매핑해 값을 세팅한다. 매핑되지 않으면 정규화 라벨을
     * extras에 보존한다({@link Labels#acceptableExtra} 오탐 가드 적용).
     */
    private static void applyLabel(NoticeRecord.Builder builder, String label, String value) {
        Optional<String> itemCd = Synonyms.canonicalFor(label);
        if (itemCd.isPresent()) {
            builder.set(itemCd.get(), value);
            return;
        }
        String normalized = Synonyms.normalizeLabel(label);
        if (Labels.acceptableExtra(normalized, value)) {
            builder.extra(normalized, value);
        }
    }

    // ── ②-2 다건 처분 블록 ───────────────────────────────────────

    /**
     * 한 파일에 고시 블록이 통째로 반복되는 서식을 처분별 레코드로 가른다.
     *
     * <p>실입력에 이런 문서가 있다 — 군산시 풍황계측기 고시 8건은 "1. 승인연월일 …"부터
     * "6. 승인을 받은 자"까지가 허가번호만 바꿔 여덟 번 반복되고, 한반도해상풍력 고시는 같은
     * 블록이 1×1 표 세 칸에 나뉘어 들어 있다. 예전에는 {@code set()}이 선착순이라 첫 블록만
     * 남고 나머지 처분이 통째로 사라졌다.
     *
     * @return 블록이 하나뿐이면 빈 목록 — 호출부가 지금까지처럼 문서 레코드 1건을 쓰게 둔다
     */
    private static List<NoticeRecord.Builder> dispositionBlocks(List<String> lines) {
        List<List<Labels.Numbered>> blocks = splitDispositions(lines);
        if (blocks.size() < 2) {
            return List.of();
        }
        List<NoticeRecord.Builder> rows = new ArrayList<>(blocks.size());
        for (List<Labels.Numbered> block : blocks) {
            NoticeRecord.Builder row = new NoticeRecord.Builder();
            for (Labels.Numbered pair : block) {
                applyLabel(row, pair.label(), pair.value());
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 처분 블록 경계를 잡는다.
     *
     * <p>경계 조건은 셋을 모두 만족해야 한다: <b>열거 번호가 1로 되돌아가고</b>,
     * 그 줄의 라벨이 <b>처분 단위 표준항목으로 매핑되고</b>, 그 항목이 <b>현재 블록에 이미
     * 값을 갖고 있다</b>. 셋 중 하나라도 빼면 붙임 목록·중첩 번호까지 물려 멀쩡한 문서가
     * 갈린다 — 실입력 1,816건에 "번호가 1로 되돌아감"만 걸면 68건이 잘못 갈라지고,
     * 셋을 다 걸면 실제 다건 처분 문서 9건만 갈라진다.
     */
    private static List<List<Labels.Numbered>> splitDispositions(List<String> lines) {
        List<List<Labels.Numbered>> blocks = new ArrayList<>();
        List<Labels.Numbered> current = new ArrayList<>();
        Set<String> filled = new HashSet<>();
        for (Labels.Numbered pair : Labels.scanNumbered(lines)) {
            String itemCd = Synonyms.canonicalFor(pair.label())
                    .filter(Mapper::isDispositionField).orElse(null);
            if (pair.number() == 1 && itemCd != null && filled.contains(itemCd)) {
                blocks.add(current);
                current = new ArrayList<>();
                filled.clear();
            }
            current.add(pair);
            if (itemCd != null) {
                filled.add(itemCd);
            }
        }
        blocks.add(current);
        return blocks;
    }

    /** 처분 단위 항목인지 — 문서 메타(기관·고시번호·제목 등)는 블록 경계 근거가 될 수 없다. */
    private static boolean isDispositionField(String itemCd) {
        return Synonyms.field(itemCd).map(Synonyms.FieldSpec::isAttribute).orElse(false);
    }

    // ── ④ 정규화 ─────────────────────────────────────────────────

    /**
     * 레코드 값을 후처리한다: 고시일·승인일 ISO 변환, 공사기간을 시작/종료로 분리,
     * 신고자 주소가 비면 성명 블록에서 주소를 보충.
     */
    private static void normalize(NoticeRecord.Builder builder, List<String> paragraphs) {
        splitApprovalNoDate(builder);
        normalizeDate(builder, "NOTI_YMD");
        normalizeDate(builder, "APPROVAL_DATE");

        String period = builder.get(Synonyms.WORK_PERIOD);
        if (period != null) {
            builder.overwrite(Synonyms.WORK_PERIOD, null);
            Optional<String[]> halves = Dates.splitRange(period);
            if (halves.isPresent()) {
                Dates.toIso(halves.get()[0]).ifPresent(v -> builder.set("WORK_PERIOD_START", v));
                Dates.toIso(halves.get()[1]).ifPresent(v -> builder.set("WORK_PERIOD_END", v));
            }
            if (!builder.has("WORK_PERIOD_START") && !builder.has("WORK_PERIOD_END")) {
                builder.extra(Synonyms.WORK_PERIOD, period);
            }
        }

        if (!builder.has("APPLICANT_ADDRESS")) {
            String name = builder.get("APPLICANT_NAME");
            if (name != null) {
                Address.extract(name).ifPresent(v -> builder.set("APPLICANT_ADDRESS", v));
            }
        }
    }

    /**
     * "승인번호(연월일)" 열/라벨 서식은 값에 번호와 일자가 같이 온다
     * (예: "제2026-26호(2026. 6. 11.)"). 끝의 괄호가 날짜로 파싱되면
     * approval_no에서 떼어 approval_date로 옮긴다.
     */
    private static void splitApprovalNoDate(NoticeRecord.Builder builder) {
        String approvalNo = builder.get("APPROVAL_NO");
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
        builder.set("APPROVAL_DATE", m.group(1).trim());
        builder.overwrite("APPROVAL_NO", number);
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
        for (String field : List.of("BODY_AGNCY_NM", "NOTI_NO", "NOTI_YMD", "NOTI_TTL", "NOTI_PSN")) {
            String value = base.get(field);
            if (value != null) {
                row.set(field, value);
            }
        }
    }
}
