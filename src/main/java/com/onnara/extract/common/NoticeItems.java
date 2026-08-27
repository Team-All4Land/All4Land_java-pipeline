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
 * 공고항목 사전 — 고시문 라벨을 표준항목으로 모은다.
 *
 * <p>{@link NoticeTypes}와 짝이다. 저쪽이 <b>문서의 종류</b>를 제목으로 판정한다면
 * 이쪽은 <b>문서 안의 항목</b>을 라벨로 판정한다. 두 사전 모두 표준어 하나에 동의어 여럿을
 * 매다는 같은 모양이고, {@code ReferenceSync}가 각각 {@code OS_NOTI_ITEM_TC}와
 * {@code OS_NOTI_KND_TC}로 동기화한다.
 *
 * <p>고시문마다 같은 필드를 다른 라벨로 부르므로(예: 고시일자/공고일자),
 * 정규화한 라벨을 itemCd 필드명으로 매핑한다. 매핑 안 된 라벨은 extras에
 * 보존되며, 빈도가 쌓이면 사전에 추가한다(§9 동의어 보강 절차).
 *
 * <p>사전 본문은 {@code src/main/resources/notice_items.json}이 단일 정의처다 —
 * 필드 설명·예시까지 함께 담아 {@code dict} 서브커맨드가 검토용 문서를 생성한다.
 * 동의어는 사람이 읽는 형태로 적고 로드 시 {@link #normalizeLabel}로 정규화하므로,
 * 사전 편집자는 정규화 규칙을 몰라도 된다.
 *
 * <p>{@code work_period}는 가상 필드 — Mapper가 기간을 start/end로 분리한다.
 */
public final class NoticeItems {

    /** 기간 가상 필드명 — Mapper가 시작/종료로 분리하므로 레코드에는 그대로 남지 않는다. */
    public static final String WORK_PRD = "WORK_PRD";

    /** {@link #WORK_PRD} 분리 결과 — 시작일(ISO). 사전에는 없는 파생 필드다. */
    public static final String WORK_PRD_ST = "WORK_PRD_ST";

    /** {@link #WORK_PRD} 분리 결과 — 종료일(ISO). 사전에는 없는 파생 필드다. */
    public static final String WORK_PRD_EN = "WORK_PRD_EN";

    /** 문서 단위 메타 — attachments 테이블의 컬럼이 된다(고시번호·고시일자·제목·고시자·기관). */
    public static final String SCOPE_ATTACHMENT = "attachment";

    /** 처분 단위 값 — OS_NOTI_ITEM_VAL_DTL 테이블의 행이 된다(표준항목 40종). */
    public static final String SCOPE_ATTRIBUTE = "attribute";

    /** 사전 리소스 경로 — 클래스패스 기준. */
    private static final String RESOURCE = "/notice_items.json";

    // 선행 기호·번호: "□" "○" "※" "-" "·" 등 머리기호와 "1." "1)" "5 "(점 생략) "가." "①".
    // 머리기호는 겹쳐 나오기도 해("□ ○ 면적") 반복 허용한다.
    // 문자 클래스는 전수 표본에서 실제로 관측된 것만 담았다 — ○ 170건, - 118건, □ 64건,
    // ※ 8건, ◦ 5건, ▷ 3건. 이들이 빠져 있으면 "□허가면적"이 "허가면적"과 다른 라벨이 된다.
    // 주의: 아래 사전 로드가 normalizeLabel을 호출하므로 이 패턴이 먼저 초기화돼야 한다.
    private static final Pattern LEADING_NUMBERING = Pattern.compile(
            "^\\s*(?:[-–—·o○□※◦▷◎▪]\\s*)*"
                    + "(?:\\d{1,2}\\s*[.)]|\\d{1,2}(?=\\s)|[가-힣]\\s*[.)]|[①-⑳㉮-㉻])?\\s*");

    /** 사전 파일에서 읽은 필드 정의 목록(선언 순서 = 문서 출력 순서). */
    private static final List<FieldSpec> FIELDS;

    /** 사전 파일의 버전 문자열 — 생성 문서에 표기한다. */
    private static final String VERSION;

    static {
        JsonNode root = readResource();
        FIELDS = loadFields(root);
        VERSION = root.path("version").asText("unknown");
    }

    /** itemCd 필드 → 정규화된 동의어 라벨 목록. */
    public static final Map<String, List<String>> LABEL_SYNONYMS = createDictionary();

    /** 사전 선언 순서의 itemCd 필드명 — 레코드 필드 정렬 기준. */
    private static final List<String> FIELD_ORDER = FIELDS.stream().map(FieldSpec::itemCd).toList();

    /** 정규화된 라벨 → itemCd 필드 (역인덱스). */
    private static final Map<String, String> LOOKUP = createLookup();

    /**
     * 사전의 필드 1건 — 매핑에 쓰이는 동의어와, 검토 문서에 쓰이는 설명·예시를 함께 담는다.
     *
     * @param itemCd   표준 필드명(= NoticeRecord 필드 키)
     * @param itemNm     사람이 읽는 필드 표시명
     * @param scope       저장 계층: {@link #SCOPE_ATTACHMENT}(문서 메타 → attachments 컬럼) 또는
     *                    {@link #SCOPE_ATTRIBUTE}(처분 단위 값 → OS_NOTI_ITEM_VAL_DTL 행).
     *                    OS_NOTI_ITEM_TC 테이블로 동기화되는 것은 후자뿐이다
     * @param series      같은 뜻을 문맥별로 다르게 부르는 항목들의 묶음(면적/기간/위치/인적/주소/
     *                    날짜/연락/사유). 누락 검증을 항목이 아니라 계열 단위로 하기 위한 축이며,
     *                    계열이 없는 단독 항목은 null
     * @param valTyCd     값의 성격 — 표준코드 CD_ITEM_VAL_TY(TEXT / DATE / DTRG / NUM).
     *                    정규화 방식을 정하고 OS_NOTI_ITEM_TC.ITEM_VAL_TY_CD로 동기화된다
     * @param core        전역 주요 항목(전수 표본 출현율 60% 이상) 여부 — 누락 검증 가중치용
     * @param virtual     NoticeRecord에 대응 필드가 없는 가상 필드 여부(work_period)
     * @param description 필드가 무엇을 담는지에 대한 설명
     * @param synonyms    정규화된 동의어 라벨 목록. 라벨로는 구별할 수 없는 항목은 비어 있을 수 있다
     * @param rawSynonyms 사전 파일에 적힌 원문 동의어 목록(문서 출력용)
     * @param examples    실제 고시문에서 관측된 값 예시
     * @param notes       검토자를 위한 주의사항(없으면 null)
     */
    public record FieldSpec(String itemCd, String itemNm, String scope, String srsNm,
                            String valTyCd, boolean coreYn, boolean virtual, boolean stored,
                            String description,
                            List<String> synonyms, List<String> rawSynonyms,
                            List<String> examples, String notes) {

        /** 이 필드가 처분 단위 값인지 — OS_NOTI_ITEM_TC 동기화 대상 판정에 쓴다. */
        public boolean isAttribute() {
            return SCOPE_ATTRIBUTE.equals(scope);
        }

        /**
         * 추출만 하고 적재하지 않는 필드인지.
         *
         * <p>BODY_AGNCY_NM·NOTI_PSN이 그렇다 — 컬럼은 뺐지만 사전에서까지 빼면 매핑이 깨진다.
         * 기관은 고시번호와 한 정규식으로 함께 잡히고, 고시자는 제목이 서명 줄을 삼키는 것을
         * 막는 가드다.
         */
        public boolean isExtractOnly() {
            return !stored;
        }
    }

    /** 인스턴스화 방지 — 정적 사전·함수만 제공하는 유틸리티 클래스. */
    private NoticeItems() {
    }

    /** 사전에 정의된 필드 목록을 파일 순서대로 반환한다(문서 생성용). */
    public static List<FieldSpec> fields() {
        return FIELDS;
    }

    /**
     * itemCd 필드명을 사전 선언 순서로 반환한다.
     *
     * <p>레코드가 필드를 이 순서로 정렬해 담으므로, 같은 서식을 표 읽는 순서가 달라도
     * {@code *.schema.json}의 키 순서가 흔들리지 않는다.
     */
    public static List<String> fieldOrder() {
        return FIELD_ORDER;
    }

    /** 사전 파일의 버전 문자열. */
    public static String version() {
        return VERSION;
    }

    /** itemCd 필드명으로 필드 정의를 찾는다. */
    public static Optional<FieldSpec> field(String itemCd) {
        return FIELDS.stream().filter(f -> f.itemCd().equals(itemCd)).findFirst();
    }

    /**
     * 사전 리소스를 읽어 필드 정의로 변환한다(클래스 로드 시 1회).
     *
     * <p>동의어는 원문 그대로 적혀 있으므로 여기서 정규화하며, 정규화 결과가
     * 비었거나 서로 다른 필드에 중복 등재되면 기동을 중단한다 — 사전이 커질 때
     * 조용히 덮어써지는 매핑 충돌을 배포 전에 잡기 위함이다.
     */
    private static List<FieldSpec> loadFields(JsonNode root) {
        JsonNode itemsNode = root.path("items");
        if (!itemsNode.isArray() || itemsNode.isEmpty()) {
            throw new IllegalStateException(RESOURCE + ": items 배열이 비어 있습니다");
        }

        List<FieldSpec> specs = new ArrayList<>();
        Map<String, String> seenSynonyms = new LinkedHashMap<>();
        Set<String> seenCanonicals = new LinkedHashSet<>();

        for (JsonNode node : itemsNode) {
            String itemCd = node.path("canonical").asText("").trim();
            if (itemCd.isEmpty()) {
                throw new IllegalStateException(RESOURCE + ": canonical이 없는 필드 항목이 있습니다");
            }
            if (!seenCanonicals.add(itemCd)) {
                throw new IllegalStateException(RESOURCE + ": canonical 필드가 중복 정의됨 — " + itemCd);
            }

            List<String> rawSynonyms = new ArrayList<>();
            List<String> normalized = new ArrayList<>();
            for (JsonNode syn : node.path("synonyms")) {
                String raw = syn.asText("");
                String norm = normalizeLabel(raw);
                if (norm.isEmpty()) {
                    throw new IllegalStateException(
                            RESOURCE + ": 정규화하면 빈 문자열이 되는 동의어 — " + itemCd + " / \"" + raw + "\"");
                }
                String owner = seenSynonyms.putIfAbsent(norm, itemCd);
                if (owner != null) {
                    throw new IllegalStateException(RESOURCE + ": 동의어 \"" + norm + "\"이(가) "
                            + owner + "와 " + itemCd + " 두 필드에 중복 등재됐습니다");
                }
                rawSynonyms.add(raw);
                normalized.add(norm);
            }
            // 동의어가 비는 것 자체는 허용한다 — 라벨로는 구별할 수 없는 표준항목이 실제로 있다
            // (subject_address: 관측 라벨이 머리기호 붙은 "주소"뿐이라 applicant_address와 겹친다).
            // 다만 사유를 notes에 적게 강제해, 실수로 빠뜨린 것과 의도적으로 비운 것을 갈라 놓는다.
            if (normalized.isEmpty() && node.path("notes").asText("").isBlank()) {
                throw new IllegalStateException(RESOURCE + ": 동의어가 하나도 없는 필드 — " + itemCd
                        + " (의도한 것이라면 notes에 사유를 적으세요)");
            }

            List<String> examples = new ArrayList<>();
            for (JsonNode ex : node.path("examples")) {
                examples.add(ex.asText(""));
            }

            JsonNode notes = node.path("notes");
            JsonNode series = node.path("series");
            specs.add(new FieldSpec(
                    itemCd,
                    node.path("display").asText(itemCd),
                    node.path("scope").asText(SCOPE_ATTRIBUTE),
                    series.isMissingNode() || series.isNull() ? null : series.asText(),
                    node.path("value_type").asText("TEXT"),
                    node.path("is_core").asBoolean(false),
                    node.path("virtual").asBoolean(false),
                    node.path("stored").asBoolean(true),
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
        try (InputStream in = NoticeItems.class.getResourceAsStream(RESOURCE)) {
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
            d.put(spec.itemCd(), spec.synonyms());
        }
        return Map.copyOf(d);
    }

    /** {@link #LABEL_SYNONYMS}를 뒤집어 "동의어 라벨 → canonical 필드" 역인덱스를 만든다. */
    private static Map<String, String> createLookup() {
        Map<String, String> m = new LinkedHashMap<>();
        LABEL_SYNONYMS.forEach((itemCd, synonyms) -> {
            for (String s : synonyms) {
                m.put(s, itemCd);
            }
        });
        return Map.copyOf(m);
    }

    /**
     * 라벨 정규화: NFC → 가운뎃점 통일 → 선행 기호·번호 제거 → 공백 제거(전각 포함) →
     * 한글 사이 마침표·쉼표를 가운뎃점으로 → 후행 콜론 제거 →
     * 토큰 사이 조사 '의' 제거는 사전 쪽에서 의/무 형태를 모두 등재하는 것으로 대신한다.
     *
     * <p><b>가운뎃점 통일이 선행 기호 제거보다 먼저다.</b> 전수 표본에는 U+2219(∙)·U+2024(․)·
     * U+2027(‧)로 시작하는 라벨이 28건 있는데, 통일을 뒤에 두면 이들이 선행 기호로 인식되지 않아
     * "∙면적"이 "·면적"으로 남아 "면적"과 다른 라벨이 된다.
     */
    public static String normalizeLabel(String raw) {
        if (raw == null) {
            return "";
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC).trim();
        s = s.replace('ㆍ', '·').replace('⋅', '·').replace('∙', '·').replace('•', '·')
                .replace('․', '·').replace('‧', '·').replace('・', '·').replace('･', '·');
        s = LEADING_NUMBERING.matcher(s).replaceFirst("");
        s = s.replaceAll("[\\s\\u00A0\\u3000]+", "");
        // OCR이 가운뎃점을 마침표/쉼표로 읽는 경우("점용.사용 장소")를 통일한다.
        // 한글 음절 사이만 바꿔 날짜("2026. 6.")나 번호 표기는 건드리지 않는다.
        s = s.replaceAll("(?<=[가-힣])[.,．，、](?=[가-힣])", "·");
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

    /** 원시 라벨 → itemCd 필드. 괄호 병기 부분까지 시도한다. */
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
