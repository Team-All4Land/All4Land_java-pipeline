package com.onnara.extract.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 라벨 동의어 사전 + 라벨 정규화.
 *
 * <p>고시문마다 같은 필드를 다른 라벨로 부르므로(예: 고시일자/공고일자),
 * 정규화한 라벨을 canonical 필드명으로 매핑한다. 매핑 안 된 라벨은 extras에
 * 보존되며, 빈도가 쌓이면 사전에 추가한다(§9 동의어 보강 절차).
 *
 * <p>사전 본문은 {@code src/main/resources/synonyms.json}이 단일 정의처다 —
 * 필드 설명·예시까지 함께 담아 {@code dict} 서브커맨드가 검토용 문서를 생성한다.
 * 동의어는 사람이 읽는 형태로 적고 로드 시 {@link #normalizeLabel}로 정규화하므로,
 * 사전 편집자는 정규화 규칙을 몰라도 된다.
 *
 * <p>{@code work_period}는 가상 필드 — Mapper가 기간을 start/end로 분리한다.
 */
public final class Synonyms {

    /** 기간 가상 필드명. NoticeRecord에는 없고 Mapper가 분리 처리한다. */
    public static final String WORK_PERIOD = "work_period";

    /** 사전 리소스 경로 — 클래스패스 기준. */
    private static final String RESOURCE = "/synonyms.json";

    // 선행 번호 목록: "1." "1)" "5 "(점 생략) "가." "나)" "①" "-" "·" 등
    // 주의: 아래 사전 로드가 normalizeLabel을 호출하므로 이 패턴이 먼저 초기화돼야 한다.
    private static final Pattern LEADING_NUMBERING = Pattern.compile(
            "^\\s*(?:[-–—·o○]\\s*)?(?:\\d{1,2}\\s*[.)]|\\d{1,2}(?=\\s)|[가-힣]\\s*[.)]|[①-⑳㉮-㉻])?\\s*");

    /** 사전 파일에서 읽은 필드 정의 목록(선언 순서 = 문서 출력 순서). */
    private static final List<FieldSpec> FIELDS;

    /** 사전 파일의 버전 문자열 — 생성 문서에 표기한다. */
    private static final String VERSION;

    static {
        JsonNode root = readResource();
        FIELDS = loadFields(root);
        VERSION = root.path("version").asText("unknown");
    }

    /** canonical 필드 → 정규화된 동의어 라벨 목록. */
    public static final Map<String, List<String>> LABEL_SYNONYMS = createDictionary();

    /** 정규화된 라벨 → canonical 필드 (역인덱스). */
    private static final Map<String, String> LOOKUP = createLookup();

    /**
     * 사전의 필드 1건 — 매핑에 쓰이는 동의어와, 검토 문서에 쓰이는 설명·예시를 함께 담는다.
     *
     * @param canonical   표준 필드명(= NoticeRecord 필드 키)
     * @param display     사람이 읽는 필드 표시명
     * @param dbColumn    대응하는 documents 테이블 컬럼명
     * @param type        값의 성격(text / date / date_range) — 정규화 방식을 설명한다
     * @param virtual     NoticeRecord에 대응 필드가 없는 가상 필드 여부(work_period)
     * @param description 필드가 무엇을 담는지에 대한 설명
     * @param synonyms    정규화된 동의어 라벨 목록
     * @param rawSynonyms 사전 파일에 적힌 원문 동의어 목록(문서 출력용)
     * @param examples    실제 고시문에서 관측된 값 예시
     * @param notes       검토자를 위한 주의사항(없으면 null)
     */
    public record FieldSpec(String canonical, String display, String dbColumn, String type,
                            boolean virtual, String description, List<String> synonyms,
                            List<String> rawSynonyms, List<String> examples, String notes) {
    }

    /** 인스턴스화 방지 — 정적 사전·함수만 제공하는 유틸리티 클래스. */
    private Synonyms() {
    }

    /** 사전에 정의된 필드 목록을 파일 순서대로 반환한다(문서 생성용). */
    public static List<FieldSpec> fields() {
        return FIELDS;
    }

    /** 사전 파일의 버전 문자열. */
    public static String version() {
        return VERSION;
    }

    /** canonical 필드명으로 필드 정의를 찾는다. */
    public static Optional<FieldSpec> field(String canonical) {
        return FIELDS.stream().filter(f -> f.canonical().equals(canonical)).findFirst();
    }

    /**
     * 사전 리소스를 읽어 필드 정의로 변환한다(클래스 로드 시 1회).
     *
     * <p>동의어는 원문 그대로 적혀 있으므로 여기서 정규화하며, 정규화 결과가
     * 비었거나 서로 다른 필드에 중복 등재되면 기동을 중단한다 — 사전이 커질 때
     * 조용히 덮어써지는 매핑 충돌을 배포 전에 잡기 위함이다.
     */
    private static List<FieldSpec> loadFields(JsonNode root) {
        JsonNode fieldsNode = root.path("fields");
        if (!fieldsNode.isArray() || fieldsNode.isEmpty()) {
            throw new IllegalStateException(RESOURCE + ": fields 배열이 비어 있습니다");
        }

        List<FieldSpec> specs = new ArrayList<>();
        Map<String, String> seenSynonyms = new LinkedHashMap<>();
        Set<String> seenCanonicals = new LinkedHashSet<>();

        for (JsonNode node : fieldsNode) {
            String canonical = node.path("canonical").asText("").trim();
            if (canonical.isEmpty()) {
                throw new IllegalStateException(RESOURCE + ": canonical이 없는 필드 항목이 있습니다");
            }
            if (!seenCanonicals.add(canonical)) {
                throw new IllegalStateException(RESOURCE + ": canonical 필드가 중복 정의됨 — " + canonical);
            }

            List<String> rawSynonyms = new ArrayList<>();
            List<String> normalized = new ArrayList<>();
            for (JsonNode syn : node.path("synonyms")) {
                String raw = syn.asText("");
                String norm = normalizeLabel(raw);
                if (norm.isEmpty()) {
                    throw new IllegalStateException(
                            RESOURCE + ": 정규화하면 빈 문자열이 되는 동의어 — " + canonical + " / \"" + raw + "\"");
                }
                String owner = seenSynonyms.putIfAbsent(norm, canonical);
                if (owner != null) {
                    throw new IllegalStateException(RESOURCE + ": 동의어 \"" + norm + "\"이(가) "
                            + owner + "와 " + canonical + " 두 필드에 중복 등재됐습니다");
                }
                rawSynonyms.add(raw);
                normalized.add(norm);
            }
            if (normalized.isEmpty()) {
                throw new IllegalStateException(RESOURCE + ": 동의어가 하나도 없는 필드 — " + canonical);
            }

            List<String> examples = new ArrayList<>();
            for (JsonNode ex : node.path("examples")) {
                examples.add(ex.asText(""));
            }

            JsonNode notes = node.path("notes");
            specs.add(new FieldSpec(
                    canonical,
                    node.path("display").asText(canonical),
                    node.path("db_column").asText(canonical),
                    node.path("type").asText("text"),
                    node.path("virtual").asBoolean(false),
                    node.path("description").asText(""),
                    List.copyOf(normalized),
                    List.copyOf(rawSynonyms),
                    List.copyOf(examples),
                    notes.isMissingNode() || notes.asText("").isBlank() ? null : notes.asText()));
        }
        return List.copyOf(specs);
    }

    /** 사전 리소스를 JSON 트리로 읽는다. 누락·파싱 실패는 기동 중단. */
    private static JsonNode readResource() {
        try (InputStream in = Synonyms.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("동의어 사전 리소스를 찾을 수 없습니다: " + RESOURCE);
            }
            return Json.MAPPER.readTree(in);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("동의어 사전을 읽을 수 없습니다: " + RESOURCE, e);
        }
    }

    /** 필드 정의에서 "canonical → 정규화 동의어 목록" 사전을 구성한다. */
    private static Map<String, List<String>> createDictionary() {
        Map<String, List<String>> d = new LinkedHashMap<>();
        for (FieldSpec spec : FIELDS) {
            d.put(spec.canonical(), spec.synonyms());
        }
        return Map.copyOf(d);
    }

    /** {@link #LABEL_SYNONYMS}를 뒤집어 "동의어 라벨 → canonical 필드" 역인덱스를 만든다. */
    private static Map<String, String> createLookup() {
        Map<String, String> m = new LinkedHashMap<>();
        LABEL_SYNONYMS.forEach((canonical, synonyms) -> {
            for (String s : synonyms) {
                m.put(s, canonical);
            }
        });
        return Map.copyOf(m);
    }

    /**
     * 라벨 정규화: NFC → 선행 번호 제거 → 공백 제거(전각 포함) →
     * 가운뎃점 통일(ㆍ⋅→·) → 후행 콜론 제거 → 토큰 사이 조사 '의' 제거는
     * 사전 쪽에서 의/무 형태를 모두 등재하는 것으로 대신한다.
     */
    public static String normalizeLabel(String raw) {
        if (raw == null) {
            return "";
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC).trim();
        s = LEADING_NUMBERING.matcher(s).replaceFirst("");
        s = s.replaceAll("[\\s\\u00A0\\u3000]+", "");
        s = s.replace('ㆍ', '·').replace('⋅', '·').replace('∙', '·').replace('•', '·')
                .replace('․', '·').replace('‧', '·').replace('・', '·').replace('･', '·');
        s = s.replaceAll("[:：]+$", "");
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * 괄호 병기 라벨 분리: "성명(상호)" → ["성명", "상호"],
     * "성명(또는명칭및주소)" → ["성명", "또는명칭및주소"]. 괄호가 없으면 원형 1개.
     */
    public static List<String> splitParenthetical(String label) {
        List<String> parts = new ArrayList<>();
        int open = label.indexOf('(');
        int close = label.lastIndexOf(')');
        if (open > 0 && close > open) {
            parts.add(label.substring(0, open));
            parts.add(label.substring(open + 1, close));
        } else {
            parts.add(label);
        }
        return parts;
    }

    /** 원시 라벨 → canonical 필드. 괄호 병기 부분까지 시도한다. */
    public static Optional<String> canonicalFor(String rawLabel) {
        String normalized = normalizeLabel(rawLabel);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        String direct = LOOKUP.get(normalized);
        if (direct != null) {
            return Optional.of(direct);
        }
        for (String part : splitParenthetical(normalized)) {
            String hit = LOOKUP.get(part);
            if (hit != null) {
                return Optional.of(hit);
            }
        }
        return Optional.empty();
    }
}
