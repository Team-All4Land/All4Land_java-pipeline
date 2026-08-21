package com.onnara.extract.common;

import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.SchemaResult;

/**
 * 매핑 결과를 DB에 적재할지 정하는 규칙 — "고시문만 적재한다"를 강제하는 지점.
 *
 * <p>입력 폴더에는 고시문만 들어오지 않는다. 전자문서 이용안내·업무편람 같은 수십 쪽짜리
 * 안내문이 같이 섞여 오는데, 이런 문서에는 기관·고시번호·점용 장소 같은 고시 항목이 없다.
 * 그대로 적재하면 표준 컬럼이 전부 빈 행이 쌓이고, 본문에서 주워 담은 라벨이 {@code extras}로
 * 흘러들어 사전 보강 통계(§docs/EXTRAS_REVIEW.md)를 오염시킨다.
 *
 * <p>추출·매핑은 정상적으로 끝낸다 — {@code raw.json}과 {@code schema.json}은 그대로 남는다.
 * 임계치를 잘못 잡아 진짜 고시문이 걸려도 산출물이 남아 있어 {@code load}로 다시 넣으면 되고,
 * 그 판단을 사람이 하도록 사유를 스키마와 로그 양쪽에 남긴다.
 */
public final class LoadPolicy {

    /**
     * 본문 글자 수 상한 기본값 — <b>반드시 실제 코퍼스로 재보정할 출발점</b>이다.
     *
     * <p>{@code samples/}와 39쪽짜리 전자문서 이용안내를 실측한 값은 이렇다.
     *
     * <pre>
     * 고시문(허가·변경허가·준공검사)   236 ~   504자
     * 방치선박 제거공고                      2,357자
     * 전자문서 이용안내(39쪽)                8,355자   ← 제외 대상
     * </pre>
     *
     * <p>안내문이 39쪽인데도 8천자대인 것은 지면 대부분이 스크린샷 이미지(30장)여서다.
     * 쪽수로 짐작한 값(수만 자)을 쓰면 걸러지지 않는다. 관측된 두 무리 사이에서 고시문 쪽에
     * 두 배 남짓 여유를 두고 잡았다 — 이 판정의 대가는 비대칭이라, 안내문 한 건이 더 들어오는
     * 쪽이 고시문 한 건이 빠지는 쪽보다 낫기 때문이다.
     *
     * <p>표본이 8건뿐이므로 이 값은 잠정이다. 매핑 결과({@code *.schema.json})마다
     * {@code body_chars}가 남으므로, 전량을 {@code pipeline --no-db}로 한 번 돌려
     * 분포를 보고 조정하면 된다(README "적재 임계치 보정" 참고).
     */
    public static final int DEFAULT_MAX_BODY_CHARS = 5_000;

    /** application.properties 키. */
    private static final String KEY = "map.max-body-chars";

    /** {@code .env} / OS 환경변수 키. */
    private static final String ENV_KEY = "MAP_MAX_BODY_CHARS";

    /** 본문 글자 수 상한. 0 이하면 제한 없음. */
    private final int maxBodyChars;

    /** 상한을 직접 지정해 규칙을 만든다(테스트·프로그램적 사용). */
    public LoadPolicy(int maxBodyChars) {
        this.maxBodyChars = maxBodyChars;
    }

    /**
     * 설정에서 규칙을 읽는다 — 우선순위는 OS 환경변수 &gt; {@code .env} &gt; application.properties.
     * 값이 숫자가 아니면 기본값으로 되돌리고 경고를 남긴다(설정 오타로 적재가 통째로 막히지 않게).
     */
    public static LoadPolicy fromProperties(AppProperties props) {
        String raw = props.getWithEnv(KEY, String.valueOf(DEFAULT_MAX_BODY_CHARS), ENV_KEY);
        try {
            return new LoadPolicy(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            System.err.println("[경고] " + KEY + " 값이 숫자가 아닙니다(" + raw + "). 기본값 "
                    + DEFAULT_MAX_BODY_CHARS + "을 씁니다.");
            return new LoadPolicy(DEFAULT_MAX_BODY_CHARS);
        }
    }

    /** 제한 없이 전부 적재하는 규칙 — 임계 판정을 끄고 싶을 때. */
    public static LoadPolicy unlimited() {
        return new LoadPolicy(0);
    }

    /** 본문 글자 수 상한(0 이하면 제한 없음). */
    public int maxBodyChars() {
        return maxBodyChars;
    }

    /**
     * raw 문서의 본문 분량을 재어 스키마에 기록하고, 상한을 넘으면 적재 제외 사유를 채운다.
     *
     * @return 판정을 반영한 같은 {@code schema} 인스턴스(호출부에서 이어 쓰기 좋게)
     */
    public SchemaResult apply(SchemaResult schema, RawDocument raw) {
        schema.setBodyCharCnt(DocumentSize.bodyCharCnt(raw));
        schema.setExclRsn(skipReasonFor(schema.getBodyCharCnt()));
        return schema;
    }

    /** 적재 제외 사유 — 대상이면 null. */
    public String skipReasonFor(int bodyCharCnt) {
        if (maxBodyChars <= 0 || bodyCharCnt <= maxBodyChars) {
            return null;
        }
        return String.format("본문 %,d자 (임계 %,d자 초과)", bodyCharCnt, maxBodyChars);
    }
}
