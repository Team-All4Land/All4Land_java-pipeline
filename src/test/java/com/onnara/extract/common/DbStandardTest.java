package com.onnara.extract.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 * 로드 시점에 검사하므로 여기서는 <b>사전과 바깥 세계의 대조</b>만 한다 — 최종 스키마를 만드는
 * 마이그레이션, 그리고 옛 이름이 남아 있는 소스.
 */
class DbStandardTest {

    /** 표준화 이후의 스키마를 확정하는 마이그레이션. */
    private static final String V3 = "/db/migration/V3__standard_naming.sql";

    /**
     * 표준화 이전 이름 — 소스 어디에도 남아 있으면 안 된다.
     *
     * <p>V1·V2는 이 이름들로 쓰였고 그게 맞으므로 검사에서 뺀다. 마이그레이션은 역사라
     * 고쳐 쓰지 않는다.
     */
    private static final List<String> RETIRED = List.of(
            "TB_AGNCY", "TB_NOTI", "TB_NOTI_KND", "TB_NOTI_ITEM",
            "TB_ATCH_FILE", "TB_ATCH_IMG", "TB_NOTI_ITEM_VAL",
            "AGNCY_NO", "KND_CD", "BOARD_CD", "UPPER_KND_NM",
            "ITEM_CD", "ITEM_NM", "SRS_NM", "VAL_TY_CD", "CORE_YN",
            "FILE_NM", "STTS_CD", "FAIL_STEP", "FAIL_KND", "FAIL_MSG", "EXCL_RSN",
            "FILE_EXTN", "REAL_EXTN", "ENGN_NM", "NOTI_YMD", "REG_DT",
            "IMG_CPTN", "FILE_PATH", "ITEM_VAL");

    /**
     * 옛 이름 검사에서 빼는 경로 — <b>옛 이름을 적는 것이 제 일인 파일들</b>이다.
     *
     * <p>V1·V2는 그 이름으로 쓰였고 그게 맞다(마이그레이션은 역사라 고쳐 쓰지 않는다).
     * V3는 옛 이름을 대야 개명을 할 수 있다. 사전과 사전 문서·이 테스트는 "무엇이 왜
     * 표준 위반이었는가"를 예로 들어 설명한다. 넷 다 테이블에 질의를 던지지 않으므로,
     * 여기에 남은 옛 이름이 무언가를 깨뜨릴 길이 없다.
     */
    private static final List<String> EXCLUDED = List.of(
            "db/migration/V1__init.sql",
            "db/migration/V2__drop_dsps_cnt.sql",
            "db/migration/V3__standard_naming.sql",
            "db/standard_terms.json",
            "docs/DbStandardDoc.java",
            "common/DbStandardTest.java");

    /** {@code CREATE DOMAIN D_X AS ...;} — 도메인 정의 한 건. */
    private static final Pattern DOMAIN_CREATE = Pattern.compile(
            "CREATE\\s+DOMAIN\\s+(D_[A-Z]+)\\s+AS\\s+([^;]+);", Pattern.CASE_INSENSITIVE);

    /** {@code ALTER COLUMN X TYPE D_Y} / {@code ADD COLUMN X D_Y} — 도메인 선언 한 줄. */
    private static final Pattern DOMAIN_DECL = Pattern.compile(
            "(?:ALTER|ADD)\\s+COLUMN\\s+([A-Z][A-Z0-9_]*)\\s+(?:TYPE\\s+)?(D_[A-Z]+)",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("사전이 로드되고 4대 구성이 모두 채워져 있다")
    void dictionaryLoads() {
        assertFalse(DbStandard.words().isEmpty(), "표준단어가 비어 있습니다");
        assertFalse(DbStandard.domains().isEmpty(), "표준도메인이 비어 있습니다");
        assertFalse(DbStandard.terms().isEmpty(), "표준용어가 비어 있습니다");
        assertFalse(DbStandard.codes().isEmpty(), "표준코드가 비어 있습니다");
        assertEquals(7, DbStandard.tables().size(), "테이블 수가 스키마와 다릅니다");
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
            if (name.startsWith("TB_") || name.startsWith("TC_")) {
                problems.add(name + ": 접두사를 쓰지 않기로 했습니다 — 유형은 접미사가 지시합니다");
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

    @Test
    @DisplayName("감사 컬럼이 전 테이블에 같은 이름으로 있다")
    void auditColumnsEverywhere() {
        List<String> problems = new ArrayList<>();
        for (DbStandard.TableSpec table : DbStandard.tables()) {
            for (String audit : DbStandard.AUDIT_COLUMNS) {
                if (!table.columns().contains(audit)) {
                    problems.add(table.physical() + ": " + audit + "이(가) 없습니다");
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n  ", problems));
    }

    @Test
    @DisplayName("V3가 사전의 모든 표준도메인을 사전이 적은 타입 그대로 만든다")
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
                problems.add(domain.id() + ": V3에 CREATE DOMAIN이 없습니다 — " + expected);
            } else if (!actual.equals(expected)) {
                problems.add(domain.id() + ": 사전은 " + expected + "인데 V3는 " + actual + "입니다");
            }
        }
        assertTrue(problems.isEmpty(), () -> "사전과 V3의 도메인이 어긋납니다:\n  "
                + String.join("\n  ", problems));
    }

    /** 공백을 모두 지운다 — 정렬 차이가 아니라 구조만 견주기 위해서다. */
    private static String squeeze(String sql) {
        return sql.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    @Test
    @DisplayName("V3의 모든 컬럼 선언이 사전의 도메인과 일치한다")
    void migrationDeclaresDictionaryDomains() throws IOException {
        String sql = readMigration();

        // ① V3가 선언한 컬럼은 전부 사전에 있고 도메인이 같아야 한다.
        //    사전을 지나치고 컬럼을 보태는 것을 여기서 막는다.
        List<String> problems = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();
        Matcher m = DOMAIN_DECL.matcher(sql);
        while (m.find()) {
            String column = m.group(1).toUpperCase(Locale.ROOT);
            String domain = m.group(2).toUpperCase(Locale.ROOT);
            declared.add(column);
            DbStandard.term(column).ifPresentOrElse(
                    term -> {
                        if (!term.domain().equals(domain)) {
                            problems.add(column + ": V3는 " + domain + "인데 사전은 " + term.domain() + "입니다");
                        }
                    },
                    () -> problems.add(column + ": V3가 선언했지만 표준용어 사전에 없습니다"));
        }

        // ② 반대로 사전의 컬럼은 전부 V3가 도메인을 선언해야 한다.
        for (DbStandard.TableSpec table : DbStandard.tables()) {
            for (String column : table.columns()) {
                if (!declared.contains(column)) {
                    problems.add(table.physical() + "." + column + ": V3가 도메인을 선언하지 않았습니다");
                }
            }
        }

        assertTrue(problems.isEmpty(), () -> "사전과 V3가 어긋납니다:\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("V3가 모든 테이블을 표준 이름으로 옮긴다")
    void migrationRenamesEveryTable() throws IOException {
        String sql = readMigration().toUpperCase(Locale.ROOT);
        List<String> missing = new ArrayList<>();
        for (DbStandard.TableSpec table : DbStandard.tables()) {
            if (!sql.contains("RENAME TO " + table.physical() + ";")) {
                missing.add(table.physical());
            }
        }
        assertTrue(missing.isEmpty(),
                () -> "V3가 옮기지 않은 테이블: " + String.join(", ", missing));
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

    /** 검사 대상 소스·리소스 파일 — 마이그레이션 역사(V1·V2)는 뺀다. */
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

    /** V3 마이그레이션 본문을 클래스패스에서 읽는다. */
    private static String readMigration() throws IOException {
        try (InputStream in = DbStandardTest.class.getResourceAsStream(V3)) {
            if (in == null) {
                throw new IOException("마이그레이션을 찾을 수 없습니다: " + V3);
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
}
