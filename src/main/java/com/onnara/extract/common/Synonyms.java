package com.onnara.extract.common;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 라벨 동의어 사전 + 라벨 정규화.
 *
 * <p>고시문마다 같은 필드를 다른 라벨로 부르므로(예: 위치/소재지/점용·사용의 장소),
 * 정규화한 라벨을 canonical 필드명으로 매핑한다. 매핑 안 된 라벨은 extras에
 * 보존되며, 빈도가 쌓이면 이 사전에 추가한다(§9 동의어 보강 절차).
 *
 * <p>{@code work_period}는 가상 필드 — Mapper가 기간을 start/end로 분리한다.
 */
public final class Synonyms {

    /** 기간 가상 필드명. NoticeRecord에는 없고 Mapper가 분리 처리한다. */
    public static final String WORK_PERIOD = "work_period";

    /** canonical 필드 → 정규화된 동의어 라벨 목록. */
    public static final Map<String, List<String>> LABEL_SYNONYMS = createDictionary();

    /** 정규화된 라벨 → canonical 필드 (역인덱스). */
    private static final Map<String, String> LOOKUP = createLookup();

    // 선행 번호 목록: "1." "1)" "5 "(점 생략) "가." "나)" "①" "-" "·" 등
    private static final Pattern LEADING_NUMBERING = Pattern.compile(
            "^\\s*(?:[-–—·o○]\\s*)?(?:\\d{1,2}\\s*[.)]|\\d{1,2}(?=\\s)|[가-힣]\\s*[.)]|[①-⑳㉮-㉻])?\\s*");

    /** 인스턴스화 방지 — 정적 사전·함수만 제공하는 유틸리티 클래스. */
    private Synonyms() {
    }

    /** canonical 필드 → 동의어 라벨 목록 사전을 구성한다(클래스 로드 시 1회). */
    private static Map<String, List<String>> createDictionary() {
        Map<String, List<String>> d = new LinkedHashMap<>();
        d.put("agency", List.of("기관", "고시기관", "관리청", "처분청"));
        d.put("notice_no", List.of("고시번호", "공고번호"));
        d.put("notice_date", List.of("고시일", "고시일자", "고시연월일", "공고일", "공고일자", "공고연월일"));
        d.put("title", List.of("제목", "고시명", "공고명"));
        d.put("signer", List.of("고시자", "공고자"));
        d.put("approval_no", List.of("승인번호", "허가번호", "신고번호", "수리번호", "허가증번호"));
        d.put("approval_date", List.of(
                "승인일", "승인일자", "승인연월일", "허가일", "허가일자", "허가연월일",
                "신고일", "수리일", "점용·사용허가일", "준공검사일"));
        d.put("location", List.of(
                "위치", "소재지", "장소", "점용·사용장소", "점용·사용의장소",
                "사업위치", "공사위치", "점용장소", "사용장소", "점용·사용위치",
                "점용지번", "발견장소"));
        d.put("area", List.of(
                "면적", "점용·사용면적", "점용·사용의면적", "점용면적",
                "사용면적", "허가면적", "준공면적"));
        d.put("work_description", List.of(
                "공사내용", "사업내용", "공사명칭", "공사명", "사업명", "공사의종류",
                "목적", "점용·사용목적", "점용·사용의목적", "점용목적", "사용목적",
                "공사의목적및개요", "사업개요", "준공시설"));
        d.put(WORK_PERIOD, List.of(
                "기간", "공사기간", "점용·사용기간", "점용·사용의기간", "점용기간",
                "사용기간", "허가기간", "공사시행기간", "사업기간"));
        d.put("applicant_name", List.of(
                "성명", "상호", "명칭", "대표자", "신고자", "신고인", "점용·사용자",
                "허가를받은자", "점용·사용허가를받은자", "피허가자", "피허가자성명",
                "피승인자", "피승인자성명", "대상자", "공사시행자",
                "공사시행자의성명", "사업시행자"));
        d.put("applicant_address", List.of(
                "주소", "신고자주소", "사업자주소", "피허가자주소", "피승인자주소"));
        d.put("remarks", List.of("비고", "기타", "참고사항"));
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
