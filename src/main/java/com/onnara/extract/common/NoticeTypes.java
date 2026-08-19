package com.onnara.extract.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 공고종류 레지스트리 — 전수 통계가 확정한 55종.
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

    static {
        JsonNode root = readResource();
        TYPES = load(root);
        VERSION = root.path("version").asText("unknown");
    }

    /**
     * 공고종류 1건.
     *
     * @param code   안정 식별자(= notice_types.type_code)
     * @param name   워크북 핵심 키워드명
     * @param family 상위 묶음 — 55종을 평면으로 두면 조회·집계가 감당되지 않는다
     */
    public record Spec(String code, String name, String family) {
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
        return TYPES.stream().filter(t -> t.code().equals(code)).findFirst();
    }

    /** 이름으로 종류를 찾는다. */
    public static Optional<Spec> byName(String name) {
        return TYPES.stream().filter(t -> t.name().equals(name)).findFirst();
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
            specs.add(new Spec(code, name, family));
        }
        return List.copyOf(specs);
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
