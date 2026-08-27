package com.onnara.extract.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * DB 표준화 사전 — 표준단어·표준도메인·표준용어·표준코드.
 *
 * <p>사전 본문은 {@code src/main/resources/db/standard_terms.json}이 단일 정의처다.
 * {@code DbStandardDoc}이 검토용 Markdown을 만들고, {@code DbStandardTest}가 실제 DDL과
 * 대조한다 — 사전이 문서로만 있으면 이름은 반드시 다시 어긋난다.
 *
 * <p><b>로드 시점에 사전 자체의 정합성을 검증한다.</b> 사전이 틀린 채 통과하면 그 사전으로
 * 검증한 DDL도 함께 틀리므로, 여기서 막지 않으면 검증이 있다는 착각만 남는다. 검증 항목:
 * 논리명·물리명 1:1, 모든 물리명이 표준단어로만 분해됨, 분류어가 지시하는 도메인과 용어의
 * 도메인 일치, 테이블 구성 컬럼이 전부 등록된 용어임, 감사 컬럼 존재, 예약어 회피.
 */
public final class DbStandard {

    /** 사전 리소스 경로 — 클래스패스 기준. */
    private static final String RESOURCE = "/db/standard_terms.json";

    /** 전 테이블에 같은 이름으로 강제하는 공통 관리(감사) 컬럼. */
    public static final List<String> AUDIT_COLUMNS = List.of("FRST_REG_DTM", "LAST_CHG_DTM");

    /** 분류어 — 물리명의 마지막 낱말이며 도메인 하나를 지시한다. */
    public static final String ROLE_CLASSIFIER = "classifier";

    /** 수식어 — 분류어 앞에 붙어 뜻을 좁힌다. */
    public static final String ROLE_MODIFIER = "modifier";

    private static final List<Word> WORDS;
    private static final List<Domain> DOMAINS;
    private static final List<TableSuffix> SUFFIXES;

    /** 주제영역(업무코드) 전체. 테이블 이름 앞에 붙는다. */
    private static final List<BusinessCode> BUSINESS_CODES;
    private static final List<TableSpec> TABLES;
    private static final List<TermSpec> TERMS;
    private static final List<CodeGroup> CODES;
    private static final Set<String> RESERVED;
    private static final String VERSION;

    /** 영문약어 → 표준단어. 물리명 분해에 쓴다. */
    private static final Map<String, Word> BY_ABBR;

    /** 도메인 ID → 도메인. */
    private static final Map<String, Domain> BY_DOMAIN_ID;

    /** 물리명 → 표준용어. */
    private static final Map<String, TermSpec> BY_PHYSICAL;

    static {
        JsonNode root = readResource();
        VERSION = root.path("version").asText("unknown");
        WORDS = loadWords(root);
        DOMAINS = loadDomains(root);
        SUFFIXES = loadSuffixes(root);
        BUSINESS_CODES = loadBusinessCodes(root);
        TERMS = loadTerms(root);
        TABLES = loadTables(root);
        CODES = loadCodes(root);
        RESERVED = loadReserved(root);

        BY_ABBR = index(WORDS, Word::abbr);
        BY_DOMAIN_ID = index(DOMAINS, Domain::id);
        BY_PHYSICAL = index(TERMS, TermSpec::physical);

        validate();
    }

    /**
     * 표준단어 1건.
     *
     * @param term    한글 표준단어
     * @param abbr    영문 약어 — 물리명에 그대로 쓰인다
     * @param english 영문명
     * @param role    {@link #ROLE_MODIFIER}(수식어) 또는 {@link #ROLE_CLASSIFIER}(분류어)
     * @param domain  분류어인 경우 지시하는 도메인 ID, 수식어면 null
     * @param notes   약어를 그렇게 정한 사유 등(없으면 null)
     */
    public record Word(String term, String abbr, String english, String role,
                       String domain, String notes,
                       List<String> synonyms, List<String> banned) {

        /** 이 낱말이 물리명의 마지막에 올 수 있는 분류어인지. */
        public boolean isClassifier() {
            return ROLE_CLASSIFIER.equals(role);
        }
    }

    /**
     * 표준도메인 1건 — 분류어 하나가 지시하는 데이터타입·길이·제약.
     *
     * @param id          도메인 ID(D_로 시작). PostgreSQL CREATE DOMAIN 이름이기도 하다
     * @param term        대응 분류어의 한글 이름
     * @param sqlType     기반 SQL 타입
     * @param check       CHECK 제약식(없으면 null)
     * @param description 이 도메인을 그 타입·길이로 정한 근거
     */
    public record Domain(String id, String term, String sqlType, String check, String description) {

        /** {@code CREATE DOMAIN} 한 줄 — 마이그레이션·문서가 같은 문자열을 쓰게 한다. */
        public String createStatement() {
            return "CREATE DOMAIN " + id + " AS " + sqlType
                    + (check == null ? "" : " CHECK (" + check + ")") + ";";
        }
    }

    /**
     * 테이블 유형 접미사 1건.
     *
     * @param suffix      접미사(언더스코어 없이)
     * @param term        한글 유형명
     * @param description 어떤 성격의 테이블에 붙이는지
     * @param used        현재 스키마에서 실제로 쓰이는지 — false는 앞으로를 위한 예비 등록이다
     */
    public record TableSuffix(String suffix, String term, String description, boolean used) {
    }

    /**
     * 주제영역(업무코드) 1건 — 테이블 이름은 {@code [업무코드]_[테이블의미]}다(OFBD-2210-01 §3.2.3).
     *
     * <p>업무코드는 <b>표준단어가 아니다.</b> 표준단어 사전은 컬럼과 테이블의미를 이루는 낱말을
     * 담는 곳이고, 업무코드는 그 앞에 붙는 별개의 분류 축이다. 여기에 섞어 두면 컬럼 이름에
     * OS가 낱말로 끼어들 수 있다.
     *
     * @param code        두 자리 축약어
     * @param term        한글 주제영역명
     * @param english     영문명
     * @param description 어떤 데이터가 이 영역에 속하는지
     * @param used        이 스키마가 실제로 쓰는 코드인지 — 하나만 true다
     */
    public record BusinessCode(String code, String term, String english,
                               String description, boolean used) {
    }

    /**
     * 표준용어(테이블) 1건.
     *
     * @param logical     논리명(한글)
     * @param physical    물리명
     * @param suffix      유형 접미사
     * @param description 무엇의 단위인지
     * @param primaryKey  PK 구성 컬럼 물리명
     * @param columns     구성 컬럼 물리명(DDL 선언 순서)
     */
    public record TableSpec(String logical, String physical, String suffix, String description,
                            List<String> primaryKey, List<String> columns) {
    }

    /**
     * 표준용어(컬럼) 1건 — 논리명과 물리명이 1:1로 대응한다.
     *
     * @param logical     논리명(한글 전체명)
     * @param physical    물리명(영문 약어 조합)
     * @param domain      도메인 ID
     * @param code        코드성 컬럼이면 코드그룹 ID, 아니면 null
     * @param audit       전 테이블 공통 관리 컬럼인지
     * @param description 이 용어가 담는 것
     */
    public record TermSpec(String logical, String physical, String domain, String code,
                           boolean audit, String description) {
    }

    /**
     * 표준코드 1군 — 코드성 속성의 허용값 집합.
     *
     * @param group       코드그룹 ID
     * @param name        코드그룹 한글명
     * @param columns     이 코드를 쓰는 컬럼(테이블.컬럼)
     * @param enforced    허용값을 강제하는지 — false면 참고 목록이다
     * @param managedBy   값 목록의 정의처가 따로 있으면 그 파일명(없으면 null)
     * @param table       값을 담는 코드 테이블 물리명(없으면 null)
     * @param description 이 코드군을 두는 이유
     * @param values      허용값. managedBy가 있으면 비어 있다 — 두 곳에 복사하면 반드시 어긋난다
     */
    public record CodeGroup(String group, String name, List<String> columns, boolean enforced,
                            String managedBy, String table, String description,
                            List<CodeValue> values) {
    }

    /**
     * 표준코드 허용값 1건.
     *
     * @param code 코드값
     * @param name 한글 뜻
     */
    public record CodeValue(String code, String name) {
    }

    /** 인스턴스화 방지 — 정적 사전·함수만 제공하는 유틸리티 클래스. */
    private DbStandard() {
    }

    /** 사전 파일의 버전 문자열. */
    public static String version() {
        return VERSION;
    }

    /** 표준단어 전체(선언 순서 = 문서 출력 순서). */
    public static List<Word> words() {
        return WORDS;
    }

    /** 표준도메인 전체. */
    public static List<Domain> domains() {
        return DOMAINS;
    }

    /** 테이블 유형 접미사 전체. */
    public static List<TableSuffix> tableSuffixes() {
        return SUFFIXES;
    }

    /** 주제영역(업무코드) 전체. */
    public static List<BusinessCode> businessCodes() {
        return BUSINESS_CODES;
    }

    /**
     * 이 스키마가 쓰는 업무코드 — 테이블 이름 앞에 붙는 두 자리다.
     *
     * @throws IllegalStateException {@code used}가 정확히 하나가 아니면
     */
    public static String businessCode() {
        List<BusinessCode> used = BUSINESS_CODES.stream().filter(BusinessCode::used).toList();
        if (used.size() != 1) {
            throw new IllegalStateException(
                    "쓰이는 업무코드는 정확히 하나여야 합니다 — 지금 " + used.size() + "개");
        }
        return used.get(0).code();
    }

    /** 표준용어(테이블) 전체. */
    public static List<TableSpec> tables() {
        return TABLES;
    }

    /** 표준용어(컬럼) 전체. */
    public static List<TermSpec> terms() {
        return TERMS;
    }

    /** 표준코드 전체. */
    public static List<CodeGroup> codes() {
        return CODES;
    }

    /** 물리명 컬럼으로 표준용어를 찾는다. */
    public static Optional<TermSpec> term(String physical) {
        return Optional.ofNullable(BY_PHYSICAL.get(physical.toUpperCase(Locale.ROOT)));
    }

    /** 도메인 ID로 도메인을 찾는다. */
    public static Optional<Domain> domain(String id) {
        return Optional.ofNullable(BY_DOMAIN_ID.get(id.toUpperCase(Locale.ROOT)));
    }

    /** 코드그룹 ID로 코드군을 찾는다. */
    public static Optional<CodeGroup> code(String group) {
        return CODES.stream().filter(c -> c.group().equals(group)).findFirst();
    }

    /**
     * 여부 도메인({@code D_YN})의 표현 — {@code true}면 {@code "Y"}, 아니면 {@code "N"}.
     *
     * <p>적재 코드가 {@code setBoolean}을 쓰면 CHAR(1) 컬럼에 넣을 수 없다. 분기를 호출부마다
     * 흩어 두면 언젠가 한 곳이 {@code "true"}를 넣고 도메인 CHECK에 걸린다.
     */
    public static String yn(boolean value) {
        return value ? "Y" : "N";
    }

    /** 여부 도메인 값이 참인지. 공백·소문자·null을 모두 거짓 아닌 쪽으로 오해하지 않는다. */
    public static boolean isYes(String value) {
        return value != null && "Y".equalsIgnoreCase(value.trim());
    }

    /** 그 이름이 SQL 예약어인지(대소문자 무시). */
    public static boolean isReserved(String identifier) {
        return RESERVED.contains(identifier.toUpperCase(Locale.ROOT));
    }

    /**
     * 물리명을 표준단어로 분해한다. 사전에 없는 약어가 하나라도 있으면 예외.
     *
     * <p>분해는 언더스코어 단위다 — 표준단어 사전이 있는 이상 경계를 추측할 이유가 없고,
     * 추측하기 시작하면 {@code NOTI_NO}가 {@code NOT}+{@code I_NO}로도 읽힌다.
     */
    public static List<Word> decompose(String physical) {
        List<Word> parsed = new ArrayList<>();
        for (String part : physical.toUpperCase(Locale.ROOT).split("_")) {
            Word word = BY_ABBR.get(part);
            if (word == null) {
                throw new IllegalArgumentException(
                        physical + ": 표준단어 사전에 없는 약어 — " + part);
            }
            parsed.add(word);
        }
        return parsed;
    }

    /**
     * 컬럼 물리명이 표준을 지키는지 검사하고, 어긋나면 사유를 담아 돌려준다.
     *
     * <p>예외가 아니라 사유 목록을 돌려주는 이유: 검증 테스트가 위반 하나에서 멈추지 않고
     * 전건을 모아 보여 줘야 고치는 사람이 한 번에 처리할 수 있다.
     *
     * @param physical    검사할 컬럼 물리명
     * @param declaredDomain DDL에 선언된 도메인 ID(모르면 null — 이름 규칙만 검사한다)
     * @return 위반 사유 목록. 비어 있으면 통과다
     */
    public static List<String> violations(String physical, String declaredDomain) {
        List<String> found = new ArrayList<>();
        if (isReserved(physical)) {
            found.add(physical + ": SQL 예약어를 식별자로 썼습니다");
        }
        if (physical.length() > 30) {
            found.add(physical + ": 30바이트를 넘습니다(" + physical.length() + ")");
        }

        List<Word> parsed;
        try {
            parsed = decompose(physical);
        } catch (IllegalArgumentException e) {
            found.add(e.getMessage());
            return found;
        }

        Word last = parsed.get(parsed.size() - 1);
        if (!last.isClassifier()) {
            found.add(physical + ": 마지막 낱말 " + last.abbr() + "(" + last.term()
                    + ")은(는) 분류어가 아닙니다 — 분류어가 없으면 이름만 보고 타입을 알 수 없습니다");
            return found;
        }
        if (declaredDomain != null && !last.domain().equalsIgnoreCase(declaredDomain)) {
            found.add(physical + ": 분류어 " + last.abbr() + "은(는) " + last.domain()
                    + "을(를) 지시하는데 DDL 선언은 " + declaredDomain + "입니다");
        }
        return found;
    }

    /** 사전 리소스를 JSON 트리로 읽는다. 누락·파싱 실패는 기동 중단. */
    private static JsonNode readResource() {
        try (InputStream in = DbStandard.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("표준 사전 리소스를 찾을 수 없습니다: " + RESOURCE);
            }
            return Json.MAPPER.readTree(in);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("표준 사전을 읽을 수 없습니다: " + RESOURCE, e);
        }
    }

    /** 표준단어 배열을 읽는다. */
    private static List<Word> loadWords(JsonNode root) {
        List<Word> list = new ArrayList<>();
        for (JsonNode n : require(root, "words")) {
            list.add(new Word(
                    text(n, "term"), text(n, "abbr"), text(n, "english"),
                    n.path("role").asText(ROLE_MODIFIER),
                    optional(n, "domain"), optional(n, "notes"),
                    strings(n, "synonyms"), strings(n, "banned")));
        }
        return List.copyOf(list);
    }

    /** 표준도메인 배열을 읽는다. */
    private static List<Domain> loadDomains(JsonNode root) {
        List<Domain> list = new ArrayList<>();
        for (JsonNode n : require(root, "domains")) {
            list.add(new Domain(text(n, "id"), text(n, "term"), text(n, "sqlType"),
                    optional(n, "check"), n.path("description").asText("")));
        }
        return List.copyOf(list);
    }

    /** 주제영역(업무코드) 배열을 읽는다. */
    private static List<BusinessCode> loadBusinessCodes(JsonNode root) {
        List<BusinessCode> list = new ArrayList<>();
        for (JsonNode n : require(root, "businessCodes")) {
            list.add(new BusinessCode(text(n, "code"), text(n, "term"),
                    n.path("english").asText(""), n.path("description").asText(""),
                    n.path("used").asBoolean(false)));
        }
        return List.copyOf(list);
    }

    /** 테이블 유형 접미사 배열을 읽는다. */
    private static List<TableSuffix> loadSuffixes(JsonNode root) {
        List<TableSuffix> list = new ArrayList<>();
        for (JsonNode n : require(root, "tableSuffixes")) {
            list.add(new TableSuffix(text(n, "suffix"), text(n, "term"),
                    n.path("description").asText(""), n.path("used").asBoolean(false)));
        }
        return List.copyOf(list);
    }

    /** 표준용어(컬럼) 배열을 읽는다. */
    private static List<TermSpec> loadTerms(JsonNode root) {
        List<TermSpec> list = new ArrayList<>();
        for (JsonNode n : require(root, "terms")) {
            list.add(new TermSpec(text(n, "logical"), text(n, "physical"), text(n, "domain"),
                    optional(n, "code"), n.path("audit").asBoolean(false),
                    n.path("description").asText("")));
        }
        return List.copyOf(list);
    }

    /** 표준용어(테이블) 배열을 읽는다. */
    private static List<TableSpec> loadTables(JsonNode root) {
        List<TableSpec> list = new ArrayList<>();
        for (JsonNode n : require(root, "tables")) {
            list.add(new TableSpec(text(n, "logical"), text(n, "physical"), text(n, "suffix"),
                    n.path("description").asText(""),
                    strings(n, "primaryKey"), strings(n, "columns")));
        }
        return List.copyOf(list);
    }

    /** 표준코드 배열을 읽는다. */
    private static List<CodeGroup> loadCodes(JsonNode root) {
        List<CodeGroup> list = new ArrayList<>();
        for (JsonNode n : require(root, "codes")) {
            List<CodeValue> values = new ArrayList<>();
            for (JsonNode v : n.path("values")) {
                values.add(new CodeValue(text(v, "code"), text(v, "name")));
            }
            list.add(new CodeGroup(text(n, "group"), text(n, "name"), strings(n, "columns"),
                    n.path("enforced").asBoolean(true), optional(n, "managedBy"),
                    optional(n, "table"), n.path("description").asText(""),
                    List.copyOf(values)));
        }
        return List.copyOf(list);
    }

    /** 예약어 목록을 읽는다. */
    private static Set<String> loadReserved(JsonNode root) {
        Set<String> set = new LinkedHashSet<>();
        for (JsonNode n : require(root, "reservedWords")) {
            set.add(n.asText().toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(set);
    }

    /**
     * 사전 자체의 정합성 검사. 하나라도 어긋나면 기동을 중단한다.
     *
     * <p>틀린 사전으로 DDL을 검증하면 통과 여부가 무의미해지므로, 사전이 먼저 옳아야 한다.
     */
    private static void validate() {
        List<String> errors = new ArrayList<>();

        for (Word w : WORDS) {
            if (w.isClassifier()) {
                if (w.domain() == null) {
                    errors.add("분류어에 도메인이 없습니다 — " + w.abbr());
                } else if (!BY_DOMAIN_ID.containsKey(w.domain())) {
                    errors.add("분류어 " + w.abbr() + "이(가) 없는 도메인을 지시합니다 — " + w.domain());
                }
            } else if (w.domain() != null) {
                errors.add("수식어에 도메인이 달려 있습니다 — " + w.abbr());
            }
        }

        Set<String> logicals = new LinkedHashSet<>();
        for (TermSpec t : TERMS) {
            if (!logicals.add(t.logical())) {
                errors.add("논리명이 중복 정의됐습니다 — " + t.logical());
            }
            errors.addAll(violations(t.physical(), t.domain()));
            if (t.code() != null && code(t.code()).isEmpty()) {
                errors.add(t.physical() + ": 없는 코드그룹을 가리킵니다 — " + t.code());
            }
        }

        Set<String> suffixes = new LinkedHashSet<>();
        for (TableSuffix s : SUFFIXES) {
            suffixes.add(s.suffix());
        }
        // 금칙어는 표준단어와 겹칠 수 없고, 한 글자여서도 안 된다 — 한 글자는 정상 한국어
        // 안에 늘 박혀 있어 검사가 오탐만 낸다(부서의 "과", 사람의 "자").
        Set<String> termNames = new java.util.LinkedHashSet<>();
        WORDS.forEach(w -> termNames.add(w.term()));
        for (Word w : WORDS) {
            for (String b : w.banned()) {
                if (b.length() < 2) {
                    errors.add(w.term() + ": 한 글자 금칙어는 두지 않는다 — " + b);
                }
                if (termNames.contains(b)) {
                    errors.add(w.term() + ": 금칙어가 표준단어와 겹칩니다 — " + b);
                }
            }
            for (String x : w.synonyms()) {
                if (termNames.contains(x)) {
                    errors.add(w.term() + ": 이음동의어가 표준단어로도 등재돼 있습니다 — " + x);
                }
            }
        }

        for (TableSpec table : TABLES) {
            if (!suffixes.contains(table.suffix())) {
                errors.add(table.physical() + ": 등록되지 않은 유형 접미사 — " + table.suffix());
            }
            if (!table.physical().endsWith("_" + table.suffix())) {
                errors.add(table.physical() + ": 이름이 유형 접미사 _" + table.suffix() + "로 끝나지 않습니다");
            }
            if (table.physical().length() > 30) {
                errors.add(table.physical() + ": 30바이트를 넘습니다");
            }
            // [업무코드]_[테이블의미]_[유형접미사]에서 가운데만 표준단어로 분해된다.
            // 업무코드는 표준단어가 아니라 주제영역이므로 먼저 떼어 낸다.
            String stem = table.physical().substring(
                    0, table.physical().length() - table.suffix().length() - 1);
            String prefix = businessCode() + "_";
            if (!stem.startsWith(prefix)) {
                errors.add(table.physical() + ": 업무코드 " + prefix + "로 시작하지 않습니다");
                continue;
            }
            stem = stem.substring(prefix.length());
            try {
                decompose(stem);
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
            for (String column : table.columns()) {
                if (!BY_PHYSICAL.containsKey(column)) {
                    errors.add(table.physical() + ": 표준용어에 없는 컬럼 — " + column);
                }
            }
            for (String pk : table.primaryKey()) {
                if (!table.columns().contains(pk)) {
                    errors.add(table.physical() + ": PK 컬럼이 구성 컬럼에 없습니다 — " + pk);
                }
            }
            for (String audit : AUDIT_COLUMNS) {
                if (!table.columns().contains(audit)) {
                    errors.add(table.physical() + ": 감사 컬럼이 없습니다 — " + audit);
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    RESOURCE + " 표준 사전이 자기모순입니다:\n  - " + String.join("\n  - ", errors));
        }
    }

    /** 배열 노드를 꺼낸다. 없거나 비어 있으면 기동 중단. */
    private static JsonNode require(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalStateException(RESOURCE + ": " + field + " 배열이 비어 있습니다");
        }
        return node;
    }

    /** 필수 문자열 필드를 꺼낸다. 비어 있으면 기동 중단. */
    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException(RESOURCE + ": " + field + "이(가) 없는 항목이 있습니다");
        }
        return value;
    }

    /** 선택 문자열 필드 — 없거나 비면 null. */
    private static String optional(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    /** 문자열 배열 필드를 목록으로. */
    private static List<String> strings(JsonNode node, String field) {
        List<String> list = new ArrayList<>();
        for (JsonNode n : node.path(field)) {
            list.add(n.asText());
        }
        return List.copyOf(list);
    }

    /** 목록을 키 함수로 색인한다. 키가 겹치면 기동 중단. */
    private static <T> Map<String, T> index(List<T> items, java.util.function.Function<T, String> key) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T item : items) {
            if (map.putIfAbsent(key.apply(item), item) != null) {
                throw new IllegalStateException(RESOURCE + ": 키가 중복됐습니다 — " + key.apply(item));
            }
        }
        return Map.copyOf(map);
    }
}
