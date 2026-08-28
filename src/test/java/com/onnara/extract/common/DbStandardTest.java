package com.onnara.extract.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 표준 사전과 실제 DDL·소스를 대조한다.
 *
 * <p>사전이 문서로만 있으면 이름은 반드시 다시 어긋난다. 여기서 막지 않으면 다음 컬럼을
 * 보태는 사람이 사전을 지나칠 것이고, 그때는 아무도 알아채지 못한다.
 *
 * <p>사전 자체의 정합성(논리명 1:1, 분류어–도메인 일치 등)은 {@link DbStandard}가 클래스
 * 로드 시점에 검사하므로 여기서는 <b>사전과 바깥 세계의 대조</b>만 한다 — 스키마를 세우는
 * {@code V1__init.sql}, 그리고 옛 이름이 남아 있는 소스.
 */
class DbStandardTest {

    /** 스키마를 세우는 마이그레이션. 하나뿐이므로 이것이 곧 최종 스키마다. */
    private static final String V1 = "/db/migration/V1__init.sql";

    /** 주제영역(업무코드) — 해양공간. 모든 테이블 이름이 이것으로 시작한다. */
    private static final String BUSINESS_CODE = "OS";

    /** 제약조건·인덱스 이름 — {@code [테이블명]_[유형][두자리]}. 기본키만 일련번호가 없다. */
    private static final Pattern CONSTRAINT_DECL = Pattern.compile(
            "CONSTRAINT\\s+([A-Z][A-Z0-9_]*)", Pattern.CASE_INSENSITIVE);

    /** {@code CREATE INDEX 이름 ON} — 인덱스 한 건. */
    private static final Pattern INDEX_DECL = Pattern.compile(
            "CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+([A-Z][A-Z0-9_]*)", Pattern.CASE_INSENSITIVE);

    /** {@code COMMENT ON TABLE 이름 IS '문장'} — 문장은 여러 줄로 이어질 수 있다. */
    private static final Pattern TABLE_COMMENT = Pattern.compile(
            "COMMENT\\s+ON\\s+TABLE\\s+([A-Z][A-Z0-9_]*)\\s+IS\\s+'((?:[^']|'')*)'",
            Pattern.CASE_INSENSITIVE);

    /**
     * {@code COMMENT ON COLUMN}을 요구하지 않는 컬럼 — 전 테이블에 같은 뜻으로 박히는 감사
     * 컬럼뿐이다. 예외를 넓히면 검사가 무의미해지므로 여기에 무엇을 보태기 전에 정말 이름만으로
     * 뜻이 다 서는지 따져야 한다.
     *
     * <p>부모 테이블에서 뜻이 정해지는 키 컬럼(자식 테이블의 {@code NOTI_SN} 등)은 목록이 아니라
     * 규칙으로 면제한다 — 어느 테이블에든 그 컬럼 주석이 한 번 달려 있으면 통과다.
     */
    private static final Set<String> SELF_EVIDENT_COLUMNS =
            Set.of("FRST_REG_DTM", "LAST_CHG_DTM");

    /** 컬럼 설명 한 건이 시작되는 자리. 형태가 하나뿐이라 정규식을 쓰지 않는다. */
    private static final String COLUMN_COMMENT_MARK = "COMMENT ON COLUMN ";

    /** 표준화 이전 이름 — 소스 어디에도 남아 있으면 안 된다. */
    private static final List<String> RETIRED = List.of(
            "TB_AGNCY", "TB_NOTI", "TB_NOTI_KND", "TB_NOTI_ITEM",
            "TB_ATCH_FILE", "TB_ATCH_IMG", "TB_NOTI_ITEM_VAL",
            "AGNCY_NO", "KND_CD", "BOARD_CD", "UPPER_KND_NM",
            "ITEM_CD", "ITEM_NM", "SRS_NM", "VAL_TY_CD", "CORE_YN",
            "FILE_NM", "STTS_CD", "FAIL_STEP", "FAIL_KND", "FAIL_MSG", "EXCL_RSN",
            "FILE_EXTN", "REAL_EXTN", "ENGN_NM", "NOTI_YMD", "REG_DT",
            "IMG_CPTN", "FILE_PATH", "ITEM_VAL",
            // 표준 명명규칙(OFBD-2210-01 §3.2.3)으로 옮기기 전 이름
            "AGNCY_BAS", "AGNCY_SN", "AGNCY_NM", "AGNCY_KND_CD", "AGNCY_BBS_URL", "CD_AGNCY_KND",
            "NOTI_BAS", "CRWL_LOG_DTL", "NOTI_KND_TC", "NOTI_ITEM_TC", "ATCH_FILE_DTL",
            "ATCH_IMG_DTL", "NOTI_ITEM_VAL_DTL", "NOTI_LBL_VAL_DTL", "iso_daterange");

    /**
     * 옛 이름 검사에서 빼는 경로 — <b>옛 이름을 적는 것이 제 일인 파일들</b>이다.
     *
     * <p>사전과 사전 문서·이 테스트는 "무엇이 왜 표준 위반이었는가"를 예로 들어 설명한다.
     * 셋 다 테이블에 질의를 던지지 않으므로 여기 남은 옛 이름이 무언가를 깨뜨릴 길이 없다.
     * 마이그레이션은 예외가 아니다 — V1이 표준 이름으로 스키마를 곧장 세우므로 옛 이름을
     * 댈 일이 없고, 대고 있다면 그것이 곧 버그다.
     */
    private static final List<String> EXCLUDED = List.of(
            "db/standard_terms.json",
            "docs/DbStandardDoc.java",
            "common/DbStandardTest.java");

    /** {@code CREATE DOMAIN D_X AS ...;} — 도메인 정의 한 건. */
    private static final Pattern DOMAIN_CREATE = Pattern.compile(
            "CREATE\\s+DOMAIN\\s+(D_[A-Z]+)\\s+AS\\s+([^;]+);", Pattern.CASE_INSENSITIVE);

    /** {@code CREATE TABLE X ( ... );} — 테이블 한 건과 그 본문. */
    private static final Pattern TABLE_BLOCK = Pattern.compile(
            "CREATE\\s+TABLE\\s+([A-Z][A-Z0-9_]*)\\s*\\((.*?)\\n\\);",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 테이블 본문의 컬럼 선언 한 줄 — {@code    COLUMN_NM  D_XX ...}. */
    private static final Pattern COLUMN_DECL = Pattern.compile(
            "^\\s+([A-Z][A-Z0-9_]*)\\s+(D_[A-Z]+)\\b", Pattern.MULTILINE);

    /**
     * 테이블 본문의 PK 제약 — 이름을 직접 주므로 컬럼 목록을 그대로 읽을 수 있다.
     *
     * <p>이름이 {@code [테이블명]_PK}라 꼬리로 가른다(OFBD-2210-01 §3.2.3).
     */
    private static final Pattern PK_DECL = Pattern.compile(
            "CONSTRAINT\\s+[A-Z0-9_]+_PK\\s+PRIMARY\\s+KEY\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("사전이 로드되고 4대 구성이 모두 채워져 있다")
    void dictionaryLoads() {
        assertFalse(DbStandard.words().isEmpty(), "표준단어가 비어 있습니다");
        assertFalse(DbStandard.domains().isEmpty(), "표준도메인이 비어 있습니다");
        assertFalse(DbStandard.terms().isEmpty(), "표준용어가 비어 있습니다");
        assertFalse(DbStandard.codes().isEmpty(), "표준코드가 비어 있습니다");
        assertEquals(9, DbStandard.tables().size(), "테이블 수가 스키마와 다릅니다");
    }

    @Test
    @DisplayName("모든 표준용어가 표준단어로 분해되고 분류어가 도메인을 지시한다")
    void everyTermDecomposes() {
        List<String> problems = new ArrayList<>();
        for (DbStandard.TermSpec term : DbStandard.terms()) {
            problems.addAll(DbStandard.violations(term.physical(), term.domain()));
        }
        assertTrue(problems.isEmpty(), () -> "표준 위반:\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("테이블명이 유형 접미사로 끝나고 30바이트를 넘지 않는다")
    void tableNamesFollowSuffixRule() {
        Set<String> suffixes = new LinkedHashSet<>();
        DbStandard.tableSuffixes().forEach(s -> suffixes.add(s.suffix()));

        List<String> problems = new ArrayList<>();
        for (DbStandard.TableSpec table : DbStandard.tables()) {
            String name = table.physical();
            if (!name.equals(name.toUpperCase(Locale.ROOT))) {
                problems.add(name + ": 대문자가 아닙니다");
            }
            if (name.getBytes(StandardCharsets.UTF_8).length > 30) {
                problems.add(name + ": 30바이트를 넘습니다");
            }
            if (suffixes.stream().noneMatch(s -> name.endsWith("_" + s))) {
                problems.add(name + ": 등록된 유형 접미사로 끝나지 않습니다 — " + suffixes);
            }
            // [업무코드]_[테이블의미] — 해양공간(OS)이 이 스키마의 업무코드다(OFBD-2210-01 §3.2.3).
            // 크롤러 DB의 OS_PUBLIC_WATERS_NOTICE도 같은 코드를 쓴다.
            if (!name.startsWith(BUSINESS_CODE + "_")) {
                problems.add(name + ": 업무코드 " + BUSINESS_CODE + "_로 시작하지 않습니다");
            }
        }
        assertTrue(problems.isEmpty(), () -> "표준 위반:\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("SQL 예약어를 테이블·컬럼 이름으로 쓰지 않는다")
    void noReservedWords() {
        List<String> problems = new ArrayList<>();
        Stream.concat(
                DbStandard.tables().stream().map(DbStandard.TableSpec::physical),
                DbStandard.terms().stream().map(DbStandard.TermSpec::physical)
        ).forEach(name -> {
            if (DbStandard.isReserved(name)) {
                problems.add(name + ": SQL 예약어입니다");
            }
        });
        assertTrue(problems.isEmpty(), () -> String.join("\n  ", problems));
    }

    /**
     * 감사 컬럼은 전 테이블 강제지만, 사전이 {@code audit:false}로 선언한 테이블은 면제다 —
     * 쌓기만 하고 고치지 않는 로그({@code OS_CRWL_LOG_DTL})가 그렇다.
     *
     * <p>면제는 <b>양방향으로</b> 검사한다. 있어야 할 곳에 없는 것만 잡으면 면제를 선언해
     * 놓고 컬럼은 그대로 둔 테이블이 조용히 통과해, 플래그가 거짓말을 하게 된다.
     */
    @Test
    @DisplayName("감사 컬럼이 면제받지 않은 전 테이블에 같은 이름으로 있다")
    void auditColumnsEverywhere() {
        List<String> problems = new ArrayList<>();
        for (DbStandard.TableSpec table : DbStandard.tables()) {
            for (String audit : DbStandard.AUDIT_COLUMNS) {
                boolean present = table.columns().contains(audit);
                if (table.audit() && !present) {
                    problems.add(table.physical() + ": " + audit + "이(가) 없습니다");
                } else if (!table.audit() && present) {
                    problems.add(table.physical() + ": 감사 면제 테이블인데 " + audit
                            + "이(가) 남아 있습니다");
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n  ", problems));
    }

    @Test
    @DisplayName("V1이 사전의 모든 표준도메인을 사전이 적은 타입 그대로 만든다")
    void migrationCreatesEveryDomain() throws IOException {
        // 정렬용 공백까지 맞추라고 하면 사전과 DDL이 글자 단위로 묶여 읽기 좋게 정돈만 해도
        // 붉게 난다. 공백을 지운 뒤 구조로 견준다.
        Map<String, String> declared = new java.util.LinkedHashMap<>();
        Matcher m = DOMAIN_CREATE.matcher(readMigration());
        while (m.find()) {
            declared.put(m.group(1).toUpperCase(Locale.ROOT), squeeze(m.group(2)));
        }

        List<String> problems = new ArrayList<>();
        for (DbStandard.Domain domain : DbStandard.domains()) {
            String expected = squeeze(domain.sqlType()
                    + (domain.check() == null ? "" : " CHECK (" + domain.check() + ")"));
            String actual = declared.get(domain.id());
            if (actual == null) {
                problems.add(domain.id() + ": V1에 CREATE DOMAIN이 없습니다 — " + expected);
            } else if (!actual.equals(expected)) {
                problems.add(domain.id() + ": 사전은 " + expected + "인데 V1은 " + actual + "입니다");
            }
        }
        assertTrue(problems.isEmpty(), () -> "사전과 V1의 도메인이 어긋납니다:\n  "
                + String.join("\n  ", problems));
    }

    /** 공백을 모두 지운다 — 정렬 차이가 아니라 구조만 견주기 위해서다. */
    private static String squeeze(String sql) {
        return sql.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    @Test
    @DisplayName("V1의 테이블마다 컬럼 구성·도메인·PK가 사전과 일치한다")
    void migrationMatchesDictionaryPerTable() throws IOException {
        Map<String, Table> declared = parseTables(readMigration());
        List<String> problems = new ArrayList<>();

        for (DbStandard.TableSpec spec : DbStandard.tables()) {
            Table table = declared.remove(spec.physical());
            if (table == null) {
                problems.add(spec.physical() + ": V1이 만들지 않습니다");
                continue;
            }
            // 컬럼은 집합이 아니라 목록으로 견준다 — 사전의 선언 순서가 곧 DDL 순서이고,
            // ERD·문서가 그 순서로 렌더링되므로 어긋나면 읽는 사람이 헷갈린다.
            if (!table.columnOrder().equals(spec.columns())) {
                problems.add(spec.physical() + ": 컬럼 구성이 사전과 다릅니다\n      사전: "
                        + spec.columns() + "\n      V1  : " + table.columnOrder());
            }
            table.domains().forEach((column, domain) ->
                    DbStandard.term(column).ifPresent(term -> {
                        if (!term.domain().equals(domain)) {
                            problems.add(spec.physical() + "." + column + ": V1은 " + domain
                                    + "인데 사전은 " + term.domain() + "입니다");
                        }
                    }));
            if (!table.primaryKey().equals(spec.primaryKey())) {
                problems.add(spec.physical() + ": PK가 사전과 다릅니다 — 사전 "
                        + spec.primaryKey() + " / V1 " + table.primaryKey());
            }
        }

        // 사전을 지나치고 테이블을 보태는 것도 막는다.
        declared.keySet().forEach(extra ->
                problems.add(extra + ": V1이 만들지만 표준용어 사전에 없습니다"));

        assertTrue(problems.isEmpty(),
                () -> "사전과 V1이 어긋납니다:\n  - " + String.join("\n  - ", problems));
    }

    /**
     * DDL의 테이블 주석도 사전 설명과 같아야 한다.
     *
     * <p>이 검사가 없어 편차가 조용히 벌어졌다 — #26이 "원본 절대경로"를 사전에 넣었을 때
     * {@code OS_ATCH_FILE_DTL}의 주석만 옛 문장으로 남았고, 다른 테이블도 하나씩 어긋나
     * 있었다. DB에 붙어 {@code \d+}로 스키마를 읽는 사람에게는 이 주석이 사실상 유일한
     * 설명이라, 여기가 옛 문장이면 사전을 갱신한 의미가 절반은 사라진다.
     *
     * <p>DDL의 두 관례를 모두 받는다 — {@code '설명'}과 {@code '논리명 — 설명'}. 설명이
     * 이미 논리명으로 시작하면 앞에 또 붙이지 않는 편이 읽기 낫기 때문이다.
     */
    @Test
    @DisplayName("V1의 테이블 주석이 사전 설명과 같다")
    void tableCommentsMatchTheDictionary() throws IOException {
        Map<String, String> comments = parseTableComments(readMigration());
        List<String> problems = new ArrayList<>();

        for (DbStandard.TableSpec spec : DbStandard.tables()) {
            String actual = comments.get(spec.physical());
            if (actual == null) {
                problems.add(spec.physical() + ": COMMENT ON TABLE이 없습니다");
                continue;
            }
            String bare = spec.description();
            String prefixed = spec.logical() + " — " + bare;
            if (!actual.equals(bare) && !actual.equals(prefixed)) {
                problems.add(spec.physical() + ": 주석이 사전과 다릅니다\n      사전: " + bare
                        + "\n      V1  : " + actual);
            }
        }

        assertTrue(problems.isEmpty(), () -> "사전과 V1 주석이 어긋납니다:\n  - "
                + String.join("\n  - ", problems)
                + "\n  설명을 고칠 곳은 db/standard_terms.json입니다 — docs/DB_STANDARD.md는"
                + " 거기서 생성됩니다.");
    }

    /** {@code COMMENT ON TABLE 이름 IS '문장'}을 읽어 낸다 — 문장 안의 {@code ''}는 따옴표 하나다. */
    private static Map<String, String> parseTableComments(String sql) {
        Map<String, String> comments = new LinkedHashMap<>();
        Matcher comment = TABLE_COMMENT.matcher(sql);
        while (comment.find()) {
            comments.put(comment.group(1).toUpperCase(Locale.ROOT),
                    comment.group(2).replace("''", "'"));
        }
        return comments;
    }

    @Test
    @DisplayName("V1이 선언한 컬럼은 전부 표준용어 사전에 있다")
    void migrationDeclaresOnlyDictionaryColumns() throws IOException {
        List<String> unknown = new ArrayList<>();
        for (Table table : parseTables(readMigration()).values()) {
            for (String column : table.columnOrder()) {
                if (DbStandard.term(column).isEmpty()) {
                    unknown.add(table.name() + "." + column);
                }
            }
        }
        assertTrue(unknown.isEmpty(), () -> "표준용어 사전에 없는 컬럼: "
                + String.join(", ", unknown) + "\n  db/standard_terms.json에 먼저 등재하세요.");
    }

    /**
     * V1이 선언한 테이블 하나.
     *
     * @param name        테이블 물리명
     * @param columnOrder 선언 순서의 컬럼 물리명
     * @param domains     컬럼 → 선언된 도메인 ID
     * @param primaryKey  PK 구성 컬럼
     */
    private record Table(String name, List<String> columnOrder,
                         Map<String, String> domains, List<String> primaryKey) {
    }

    /**
     * DDL에서 {@code CREATE TABLE} 블록을 읽어 낸다.
     *
     * <p>SQL 파서를 붙이지 않는 이유: V1은 사람이 읽으라고 한 가지 형식으로만 적혀 있고
     * (컬럼 한 줄에 하나, PK는 이름을 준 테이블 제약), 그 형식을 벗어나면 여기서 걸리는 편이
     * 오히려 낫다 — 형식이 흔들리면 사람이 읽는 비용이 먼저 오른다.
     */
    private static Map<String, Table> parseTables(String sql) {
        Map<String, Table> tables = new java.util.LinkedHashMap<>();
        Matcher block = TABLE_BLOCK.matcher(sql);
        while (block.find()) {
            String name = block.group(1).toUpperCase(Locale.ROOT);
            String body = block.group(2);

            List<String> order = new ArrayList<>();
            Map<String, String> domains = new java.util.LinkedHashMap<>();
            Matcher column = COLUMN_DECL.matcher(body);
            while (column.find()) {
                String columnName = column.group(1).toUpperCase(Locale.ROOT);
                order.add(columnName);
                domains.put(columnName, column.group(2).toUpperCase(Locale.ROOT));
            }

            List<String> pk = List.of();
            Matcher primaryKey = PK_DECL.matcher(body);
            if (primaryKey.find()) {
                pk = java.util.Arrays.stream(primaryKey.group(1).split(","))
                        .map(part -> part.trim().toUpperCase(Locale.ROOT))
                        .filter(part -> !part.isEmpty())
                        .toList();
            }
            tables.put(name, new Table(name, List.copyOf(order), Map.copyOf(domains), pk));
        }
        return tables;
    }

    /**
     * 제약조건·인덱스 이름이 표준 형식인지 — {@code [테이블명]_[유형][두자리]}.
     *
     * <p>OFBD-2210-01 §3.2.3이 정한 형식이다. 기본키만 일련번호가 없고(테이블당 하나뿐이므로),
     * 나머지는 테이블마다 01부터 매긴다. 일련번호는 이름만 봐서 무엇을 위한 것인지 알려 주지
     * 못하므로 인덱스에는 {@code COMMENT ON INDEX}를 함께 단다 — 그것도 여기서 검사한다.
     */
    @Test
    @DisplayName("제약조건·인덱스 이름이 [테이블명]_[유형][두자리] 형식이다")
    void constraintNamesFollowTheStandard() throws IOException {
        String sql = readMigration();
        Set<String> tables = new LinkedHashSet<>();
        DbStandard.tables().forEach(t -> tables.add(t.physical()));

        List<String> problems = new ArrayList<>();
        Matcher constraint = CONSTRAINT_DECL.matcher(sql);
        while (constraint.find()) {
            String name = constraint.group(1).toUpperCase(Locale.ROOT);
            String owner = tables.stream().filter(t -> name.startsWith(t + "_"))
                    .max(Comparator.comparingInt(String::length)).orElse(null);
            if (owner == null) {
                problems.add(name + ": 테이블명으로 시작하지 않습니다");
                continue;
            }
            String tail = name.substring(owner.length() + 1);
            if (!tail.equals("PK") && !tail.matches("(FK|UK|CK)\\d{2}")) {
                problems.add(name + ": 꼬리가 PK / FK·UK·CK+두자리가 아닙니다 — " + tail);
            }
        }

        Matcher index = INDEX_DECL.matcher(sql);
        while (index.find()) {
            String name = index.group(1).toUpperCase(Locale.ROOT);
            String owner = tables.stream().filter(t -> name.startsWith(t + "_"))
                    .max(Comparator.comparingInt(String::length)).orElse(null);
            if (owner == null || !name.substring(owner.length() + 1).matches("(IX|UK)\\d{2}")) {
                problems.add(name + ": [테이블명]_IX00 형식이 아닙니다");
            } else if (!sql.contains("COMMENT ON INDEX " + name + " IS")) {
                // 일련번호가 뜻을 지웠으므로 주석이 그 자리를 메워야 한다
                problems.add(name + ": COMMENT ON INDEX가 없습니다 — 이름만으로는 용도를 알 수 없습니다");
            }
        }

        // 이름 길이는 컬럼과 같은 30바이트를 넘지 않는다
        Matcher all = Pattern.compile("CONSTRAINT\\s+([A-Z][A-Z0-9_]*)|CREATE\\s+INDEX\\s+([A-Z][A-Z0-9_]*)",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        while (all.find()) {
            String name = all.group(1) != null ? all.group(1) : all.group(2);
            if (name.getBytes(StandardCharsets.UTF_8).length > 30) {
                problems.add(name + ": 30바이트를 넘습니다");
            }
        }
        assertTrue(problems.isEmpty(), () -> "명명규칙 위반:\n  " + String.join("\n  ", problems));
    }

    /**
     * 금칙어가 논리명에 새어 들어오지 않았는지.
     *
     * <p>지침은 이음동의어 중 하나만 표준어로 쓰고 나머지는 금칙어로 등록해 사용을 막으라고
     * 한다(OFBD-3210-02 §1.4.1). 금칙어를 문서로만 두면 다시 새어 들어오므로 여기서 잡는다.
     *
     * <p>검사 대상은 <b>논리명뿐</b>이다. 설명문까지 훑으면 "에러 원인"처럼 금칙어를 설명하려고
     * 쓴 문장이 걸려 오탐만 낸다 — 규칙이 지켜야 할 것은 이름이지 산문이 아니다.
     */
    @Test
    @DisplayName("금칙어가 논리명에 들어 있지 않다")
    void bannedWordsStayOutOfLogicalNames() {
        Map<String, String> banned = new LinkedHashMap<>();
        for (DbStandard.Word w : DbStandard.words()) {
            w.banned().forEach(b -> banned.put(b, w.term()));
        }
        assertFalse(banned.isEmpty(), "금칙어가 하나도 등재돼 있지 않습니다");

        List<String> names = new ArrayList<>();
        DbStandard.terms().forEach(t -> names.add(t.logical()));
        DbStandard.tables().forEach(t -> names.add(t.logical()));
        DbStandard.codes().forEach(c -> names.add(c.name()));

        List<String> problems = new ArrayList<>();
        for (String name : names) {
            banned.forEach((bad, good) -> {
                if (name.contains(bad)) {
                    problems.add(name + ": 금칙어 \"" + bad + "\" — 표준어는 \"" + good + "\"입니다");
                }
            });
        }
        assertTrue(problems.isEmpty(), () -> "금칙어 위반:\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("표준화 이전 이름이 소스·리소스에 남아 있지 않다")
    void noRetiredNamesInSources() throws IOException {
        // 낱말 경계는 언더스코어까지 포함해 본다 — 그러지 않으면 NOTI_ITEM_CD 안의
        // ITEM_CD가 걸려 고칠 것이 없는데도 붉게 난다.
        List<Pattern> patterns = RETIRED.stream()
                .map(name -> Pattern.compile("(?<![A-Za-z0-9_])" + name + "(?![A-Za-z0-9_])"))
                .toList();

        List<String> hits = new ArrayList<>();
        for (Path file : sourceFiles()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            List<String> lines = text.lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                for (int p = 0; p < patterns.size(); p++) {
                    if (patterns.get(p).matcher(lines.get(i)).find()) {
                        hits.add(file + ":" + (i + 1) + "  " + RETIRED.get(p)
                                + "  |  " + lines.get(i).strip());
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), () -> "표준화 이전 이름이 남아 있습니다:\n  "
                + String.join("\n  ", hits));
    }

    /** 검사 대상 소스·리소스 파일. */
    private static List<Path> sourceFiles() throws IOException {
        List<Path> roots = List.of(
                Path.of("src/main/java"), Path.of("src/main/resources"), Path.of("src/test/java"));
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.endsWith(".java") || name.endsWith(".json")
                                    || name.endsWith(".sql");
                        })
                        .filter(p -> EXCLUDED.stream()
                                .noneMatch(ex -> p.toString().replace('\\', '/').endsWith(ex)))
                        .sorted()
                        .forEach(files::add);
            }
        }
        return files;
    }

    /** V1 마이그레이션 본문을 클래스패스에서 읽는다. */
    private static String readMigration() throws IOException {
        try (InputStream in = DbStandardTest.class.getResourceAsStream(V1)) {
            if (in == null) {
                throw new IOException("마이그레이션을 찾을 수 없습니다: " + V1);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("표준코드가 가리키는 컬럼이 실제로 그 코드를 쓴다")
    void codeGroupsPointAtRealColumns() {
        Map<String, DbStandard.TableSpec> byName = new java.util.LinkedHashMap<>();
        DbStandard.tables().forEach(t -> byName.put(t.physical(), t));

        List<String> problems = new ArrayList<>();
        for (DbStandard.CodeGroup group : DbStandard.codes()) {
            for (String ref : group.columns()) {
                String[] parts = ref.split("\\.", 2);
                if (parts.length != 2) {
                    problems.add(group.group() + ": 컬럼 표기가 테이블.컬럼이 아닙니다 — " + ref);
                    continue;
                }
                DbStandard.TableSpec table = byName.get(parts[0]);
                if (table == null) {
                    problems.add(group.group() + ": 없는 테이블 — " + parts[0]);
                } else if (!table.columns().contains(parts[1])) {
                    problems.add(group.group() + ": " + parts[0] + "에 없는 컬럼 — " + parts[1]);
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n  ", problems));
    }

    /**
     * 코드표에 실측 0건인 값이 섞이면 표 전체를 믿을 수 없게 된다. 정의처가 코드(enum)인
     * 그룹은 사전과 양방향으로 맞춰 둔다 — 지금까지는 손으로만 맞춰져 있었고,
     * {@code CD_FAIL_STEP}에는 실제로 산출되지 않는 값이 둘 남아 있었다.
     */
    @Test
    @DisplayName("정의처가 enum인 표준코드는 사전과 값 집합이 같다")
    void enumBackedCodeGroupsMatchTheirSource() {
        assertCodeGroupMatches("CD_FAIL_STEP",
                java.util.Arrays.stream(LoadStep.values()).map(LoadStep::code).toList());
        assertCodeGroupMatches("CD_FAIL_KND",
                java.util.Arrays.stream(com.onnara.extract.detect.FailureKind.values())
                        .map(Enum::name).toList());
    }

    /**
     * {@code CD_ITEM_VAL_TY}의 정의처는 {@code notice_items.json}의 {@code value_type}이다.
     * 아무 항목도 쓰지 않는 유형이 표에 남아 있으면(한때 {@code NUM}이 그랬다) 정의처가
     * 정의처 노릇을 못 한 것이다.
     */
    @Test
    @DisplayName("항목값유형코드는 notice_items.json이 실제로 쓰는 유형과 같다")
    void itemValueTypesMatchNoticeItems() {
        assertCodeGroupMatches("CD_ITEM_VAL_TY",
                NoticeItems.fields().stream().map(NoticeItems.FieldSpec::valTyCd)
                        .filter(v -> v != null && !v.isBlank()).distinct().toList());
    }

    /** 사전 등재값과 정의처 값 집합을 양방향으로 대조한다. */
    private static void assertCodeGroupMatches(String group, List<String> actual) {
        Set<String> registered = DbStandard.codes().stream()
                .filter(g -> g.group().equals(group))
                .findFirst()
                .orElseThrow(() -> new AssertionError("사전에 없는 코드 그룹: " + group))
                .values().stream().map(DbStandard.CodeValue::code)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> source = new LinkedHashSet<>(actual);

        Set<String> onlyRegistered = new LinkedHashSet<>(registered);
        onlyRegistered.removeAll(source);
        Set<String> onlySource = new LinkedHashSet<>(source);
        onlySource.removeAll(registered);

        assertTrue(onlyRegistered.isEmpty(), () -> group
                + ": 정의처가 내놓지 않는 값이 사전에 있습니다 — " + onlyRegistered);
        assertTrue(onlySource.isEmpty(), () -> group
                + ": 정의처에는 있는데 사전에 없습니다 — " + onlySource);
    }

    /**
     * 갈래를 만들어 놓고 아무 데서도 그 값을 내놓지 않으면 코드표는 있는데 데이터는 영영
     * 안 생긴다. {@code FailureKind.ENCRYPTED}가 그랬다 — 분류기가 그 자리를
     * {@code PASSWORD_PROTECTED}로 보내고 있었는데, 주석은 ENCRYPTED로 간다고 적고 있었다.
     */
    @Test
    @DisplayName("실패종류 갈래는 전부 분류기가 실제로 내놓는다")
    void everyFailureKindIsProduced() throws IOException {
        String classifier = Files.readString(
                Path.of("src/main/java/com/onnara/extract/detect/FailureClassifier.java"),
                StandardCharsets.UTF_8);

        List<String> unreachable = java.util.Arrays.stream(
                        com.onnara.extract.detect.FailureKind.values())
                .map(Enum::name)
                .filter(name -> !classifier.contains("FailureKind." + name))
                .toList();

        assertTrue(unreachable.isEmpty(), () -> "분류기가 한 번도 내놓지 않는 갈래입니다"
                + " — 배선이 끊겼거나 갈래를 지워야 합니다:\n  " + String.join("\n  ", unreachable));
    }

    /**
     * 이름만으로는 컬럼이 무엇을 담는지 알려 주지 못한다. {@code EXCL_RSN_CTNT}가 단순 사유가
     * 아니라 임계 재보정에 쓰는 측정값이라는 사실이 주석이 없어 아무 데도 없었고, 그래서
     * 그 컬럼이 필요 없다는 오해가 나왔다. 인덱스에 {@code COMMENT ON INDEX}를 요구하는 것과
     * 같은 이유로 컬럼에도 요구한다.
     */
    @Test
    @DisplayName("모든 컬럼에 COMMENT ON COLUMN이 있다")
    void everyColumnIsCommented() throws IOException {
        String sql = readMigration();
        // 어느 테이블에든 한 번 설명된 컬럼은 통과다 — 자식 테이블이 빌려 쓰는 키까지
        // 같은 문장을 반복시키면 주석이 늘어날 뿐 읽히지 않는다.
        Set<String> documented = new LinkedHashSet<>();
        for (String tail : sql.split(COLUMN_COMMENT_MARK)) {
            int dot = tail.indexOf('.');
            int is = tail.indexOf(" IS");
            if (dot > 0 && is > dot) {
                documented.add(tail.substring(dot + 1, is).trim());
            }
        }

        List<String> missing = new ArrayList<>();
        for (DbStandard.TableSpec table : DbStandard.tables()) {
            for (String column : table.columns()) {
                if (SELF_EVIDENT_COLUMNS.contains(column) || documented.contains(column)) {
                    continue;
                }
                missing.add(table.physical() + "." + column);
            }
        }
        assertTrue(missing.isEmpty(), () -> "COMMENT ON COLUMN이 없습니다 —"
                + " 이름만으로는 무엇을 담는지 알 수 없습니다:\n  " + String.join("\n  ", missing));
    }
}
