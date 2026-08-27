package com.onnara.extract.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * 공고종류 레지스트리 — 전수 통계가 확정한 55종 + 실입력으로 보탠 1종.
 *
 * <p>{@code notice_types.json}이 단일 정의처이고, {@link com.onnara.extract.db.ReferenceSync}가
 * 기동 시 {@code notice_types} 테이블로 동기화한다. {@link Synonyms}와 같은 방식(정적 로딩 +
 * 기동 시 검증)을 쓴다 — 정의처가 코드 쪽에 있고 DB는 그 사본이라는 규칙을 두 사전이 함께 따른다.
 *
 * <p>종류가 왜 별도 축이어야 하는지: 한 기관이 평균 12종을 발행하고(창원시청 32종),
 * 같은 기관 안에서도 종류가 바뀌면 등장 항목이 통째로 달라진다. 인천지방해양수산청의
 * 점용·사용 허가는 면적·기간이 필수인데 허가취소는 취소일자 하나뿐이다. 종류를 모르면
 * 허가취소 문서에 면적이 없는 것을 누락으로 오탐한다.
 */
public final class NoticeTypes {

    /** 레지스트리 리소스 경로 — 클래스패스 기준. */
    private static final String RESOURCE = "/notice_types.json";

    /** 파일 선언 순서의 종류 목록(= 표본 건수 내림차순). */
    private static final List<Spec> TYPES;

    /** 레지스트리 파일의 버전 문자열. */
    private static final String VERSION;

    /** priority 내림차순 사본 — classify가 매번 정렬하지 않도록 한 번만 만든다. */
    private static final List<Spec> BY_PRIORITY;

    /**
     * 근거법령 이름 — 판정 전에 지운다.
     *
     * <p>「공유수면 관리 및 <b>매립</b>에 관한 법률」이라는 이름 자체에 매립이 들어 있다.
     * 그냥 두면 "…법률 제8조에 따라 다음과 같이 공유수면 점용·사용 허가를 …" 같은 문장이
     * 제목 자리에 왔을 때 점용·사용 허가 문서가 매립으로 분류된다. 실입력에서 매립이 든
     * 제목 135건 중 124건이 이 법률명 때문이었다.
     */
    private static final Pattern LAW_NAME =
            Pattern.compile("공유수면관리및매립에관한법[률령]");

    /** 제목 정규화에서 지우는 가운뎃점류와 공백. */
    private static final Pattern SEPARATORS = Pattern.compile("[·ㆍ․‧∙\\s]+");

    static {
        JsonNode root = readResource();
        TYPES = load(root);
        VERSION = root.path("version").asText("unknown");
        BY_PRIORITY = TYPES.stream()
                .filter(t -> !t.keywords().isEmpty())
                .sorted(Comparator.comparingInt(Spec::priority).reversed())
                .toList();
    }

    /**
     * 공고종류 1건.
     *
     * @param notiKndCd  안정 식별자(= OS_NOTI_KND_TC.NOTI_KND_CD)
     * @param notiKndNm  워크북 핵심 키워드명
     * @param upperKndNm 상위 묶음 — 종류를 평면으로 두면 조회·집계가 감당되지 않는다
     */
    public record Spec(String notiKndCd, String notiKndNm, String upperKndNm,
                       List<List<String>> keywords, int priority) {
    }

    /**
     * 제목 비교용 정규화 — 가운뎃점류와 공백을 지우고 약어를 편다.
     *
     * <p>같은 말이 서식마다 네 가지로 적힌다: {@code 점용ㆍ사용}, {@code 점용․사용},
     * {@code 점용·사용}, {@code 점․사용}. 접지 않으면 규칙을 네 벌 써야 한다.
     *
     * <p>공개해 두는 이유: 미분류 제목 리포트가 같은 기준으로 제목을 묶어야, 표에 뜬 제목과
     * {@code keywords}에 적을 낱말이 어긋나지 않는다.
     */
    public static String normalizeTitle(String title) {
        String compact = SEPARATORS.matcher(title).replaceAll("");
        String folded = compact.replace("점용사용", "점사용")
                .replace("점용", "점사용")
                .replace("점사용사용", "점사용")
                .toLowerCase(Locale.ROOT);
        return LAW_NAME.matcher(folded).replaceAll("");
    }

    /**
     * 고시문 제목으로 공고종류를 판정한다.
     *
     * <p>{@code priority} 내림차순으로 훑어 첫 매칭을 쓴다 — 구체적인 규칙이 먼저다.
     * 한 규칙 안의 {@code "가+나"}는 두 낱말이 모두 있어야 하고, 규칙끼리는 OR다.
     *
     * <p>못 가리면 비워 둔다. 어느 종류에도 자리가 없는 문서가 실제로 섞여 있어,
     * 억지로 가장 비슷한 종류에 밀어 넣으면 종류별 집계가 조용히 오염된다.
     */
    public static Optional<String> classify(String title) {
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalizeTitle(title);
        for (Spec spec : BY_PRIORITY) {
            for (List<String> words : spec.keywords()) {
                if (words.stream().allMatch(normalized::contains)) {
                    return Optional.of(spec.notiKndCd());
                }
            }
        }
        return Optional.empty();
    }

    /** 인스턴스화 방지 — 정적 레지스트리만 제공하는 유틸리티 클래스. */
    private NoticeTypes() {
    }

    /** 정의된 공고종류를 파일 순서대로 반환한다. */
    public static List<Spec> all() {
        return TYPES;
    }

    /** 레지스트리 파일의 버전 문자열. */
    public static String version() {
        return VERSION;
    }

    /** 코드로 종류를 찾는다. */
    public static Optional<Spec> byCode(String code) {
        return TYPES.stream().filter(t -> t.notiKndCd().equals(code)).findFirst();
    }

    /** 이름으로 종류를 찾는다. */
    public static Optional<Spec> byName(String name) {
        return TYPES.stream().filter(t -> t.notiKndNm().equals(name)).findFirst();
    }

    /**
     * 레지스트리를 읽어 종류 목록으로 변환한다(클래스 로드 시 1회).
     *
     * <p>코드·이름 중복은 기동을 중단시킨다 — 중복이 있으면 어느 쪽으로 매핑될지가 선언
     * 순서에 좌우되고, DB로 동기화할 때 조용히 덮어써진다.
     */
    private static List<Spec> load(JsonNode root) {
        JsonNode node = root.path("types");
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalStateException(RESOURCE + ": types 배열이 비어 있습니다");
        }

        List<Spec> specs = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode t : node) {
            String code = t.path("code").asText("").trim();
            String name = t.path("name").asText("").trim();
            String family = t.path("family").asText("").trim();
            if (code.isEmpty() || name.isEmpty() || family.isEmpty()) {
                throw new IllegalStateException(
                        RESOURCE + ": code/name/family가 비어 있는 항목이 있습니다 — " + code + " / " + name);
            }
            if (!codes.add(code)) {
                throw new IllegalStateException(RESOURCE + ": 코드가 중복 정의됨 — " + code);
            }
            if (!names.add(name)) {
                throw new IllegalStateException(RESOURCE + ": 이름이 중복 정의됨 — " + name);
            }
            specs.add(new Spec(code, name, family, keywordsOf(t, code), t.path("priority").asInt(0)));
        }
        return List.copyOf(specs);
    }

    /**
     * 종류 하나의 제목 판정 규칙을 읽는다. {@code "가+나"} → {@code ["가","나"]}.
     *
     * <p>빈 규칙은 허용한다 — 제목만으로는 못 가리는 종류가 있고, 그런 종류에 억지 규칙을
     * 붙이면 다른 종류를 가로챈다. 다만 규칙이 있는데 낱말이 비면 기동을 중단한다:
     * 빈 낱말 목록은 {@code allMatch}에서 항상 참이라 그 종류가 모든 제목을 삼킨다.
     */
    private static List<List<String>> keywordsOf(JsonNode type, String code) {
        List<List<String>> rules = new ArrayList<>();
        for (JsonNode rule : type.path("keywords")) {
            List<String> words = new ArrayList<>();
            for (String word : rule.asText("").split("\\+")) {
                String trimmed = word.trim();
                if (!trimmed.isEmpty()) {
                    words.add(NoticeTypes.normalizeTitle(trimmed));
                }
            }
            if (words.isEmpty()) {
                throw new IllegalStateException(
                        RESOURCE + ": 낱말이 없는 규칙이 있습니다 — " + code
                                + " (빈 규칙은 모든 제목에 매칭돼 다른 종류를 가로챕니다)");
            }
            rules.add(List.copyOf(words));
        }
        return List.copyOf(rules);
    }

    /** 레지스트리 리소스를 JSON 트리로 읽는다. 누락·파싱 실패는 기동 중단. */
    private static JsonNode readResource() {
        try (InputStream in = NoticeTypes.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("공고종류 레지스트리를 찾을 수 없습니다: " + RESOURCE);
            }
            return Json.MAPPER.readTree(in);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("공고종류 레지스트리를 읽을 수 없습니다: " + RESOURCE, e);
        }
    }
}
