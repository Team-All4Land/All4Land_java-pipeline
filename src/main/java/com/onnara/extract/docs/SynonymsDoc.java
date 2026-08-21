package com.onnara.extract.docs;

import com.onnara.extract.common.Synonyms;

import java.util.List;
import java.util.StringJoiner;

/**
 * 동의어 사전을 검토용 Markdown 문서로 렌더링한다.
 *
 * <p>사전 본문은 {@code src/main/resources/synonyms.json}이고 이 문서는 그 파생물이다.
 * 사전을 고칠 때마다 문서를 손으로 맞추면 어긋나므로, 문서는 항상 생성해서 쓴다.
 */
public final class SynonymsDoc {

    /** 인스턴스화 방지 — 정적 렌더링 함수만 제공하는 유틸리티 클래스. */
    private SynonymsDoc() {
    }

    /** 사전 전체를 Markdown 문서 문자열로 렌더링한다. */
    public static String render() {
        List<Synonyms.FieldSpec> fields = Synonyms.fields();
        int synonymCount = fields.stream().mapToInt(f -> f.synonyms().size()).sum();

        StringBuilder md = new StringBuilder();
        md.append("# 동의어 사전 — 고시문 라벨 → 표준 필드\n\n");
        md.append("> **자동 생성 문서입니다.** 직접 고치지 마세요.\n")
                .append("> 사전 본문은 `src/main/resources/synonyms.json`이며, 고친 뒤\n")
                .append("> `java -jar extract.jar dict`를 다시 실행하면 이 문서가 갱신됩니다.\n\n");

        md.append("| 항목 | 값 |\n|---|---|\n");
        md.append("| 사전 버전 | `").append(Synonyms.version()).append("` |\n");
        md.append("| 표준 필드 | ").append(fields.size()).append("개 |\n");
        md.append("| 등록된 동의어 | ").append(synonymCount).append("개 |\n\n");

        md.append(overview(fields));
        md.append(details(fields));
        md.append(normalizationRules());
        md.append(howToExtend());
        return md.toString();
    }

    /** 필드 한 줄 요약표 — 어떤 필드가 있고 어느 계층·계열로 가는지 한눈에 본다. */
    private static String overview(List<Synonyms.FieldSpec> fields) {
        StringBuilder md = new StringBuilder("## 한눈에 보기\n\n");
        md.append("| # | 표준 필드 | 표시명 | 저장 계층 | 계열 | 값 형식 | 주요 | 동의어 수 |\n");
        md.append("|---:|---|---|---|---|---|:-:|---:|\n");
        int i = 1;
        for (Synonyms.FieldSpec f : fields) {
            md.append("| ").append(i++)
                    .append(" | `").append(f.itemCd()).append('`')
                    .append(" | ").append(Markdown.cell(f.itemNm()))
                    .append(" | ").append(scopeLabel(f))
                    .append(" | ").append(f.srsNm() == null ? "—" : Markdown.cell(f.srsNm()))
                    .append(" | ").append(typeLabel(f))
                    .append(" | ").append(f.coreYn() ? "●" : "")
                    .append(" | ").append(f.synonyms().size())
                    .append(" |\n");
        }
        md.append("\n");
        return md.toString();
    }

    /** 저장 계층 표기 — 항목 행인지, 첨부 컬럼인지, 아니면 적재하지 않는지. */
    private static String scopeLabel(Synonyms.FieldSpec f) {
        if (f.isAttribute()) {
            return "`TB_NOTI_ITEM_VAL` 행";
        }
        // 추출은 하되 어디에도 넣지 않는 필드가 있다. 컬럼으로 적으면 없는 컬럼을 찾게 된다
        return f.isExtractOnly() ? "추출만(적재 안 함)" : "`TB_ATCH_FILE` 컬럼";
    }

    /** 필드별 상세 — 설명·예시·주의사항과 인식하는 라벨 전체 목록. */
    private static String details(List<Synonyms.FieldSpec> fields) {
        StringBuilder md = new StringBuilder("## 필드별 상세\n\n");
        for (Synonyms.FieldSpec f : fields) {
            md.append("### `").append(f.itemCd()).append("` — ").append(f.itemNm()).append("\n\n");
            md.append("- **저장 계층**: ").append(scopeLabel(f)).append('\n');
            if (f.srsNm() != null) {
                md.append("- **계열**: ").append(f.srsNm())
                        .append(" — 누락 검증은 항목이 아니라 계열 단위로 본다\n");
            }
            md.append("- **값 형식**: ").append(typeLabel(f)).append('\n');
            if (f.coreYn()) {
                md.append("- **주요 항목**: 전수 표본 출현율 60% 이상\n");
            }
            if (!f.description().isBlank()) {
                md.append("- **설명**: ").append(f.description()).append('\n');
            }
            if (!f.examples().isEmpty()) {
                StringJoiner examples = new StringJoiner("`, `", "`", "`");
                f.examples().forEach(examples::add);
                md.append("- **값 예시**: ").append(examples).append('\n');
            }
            if (f.notes() != null) {
                md.append("- **검토 메모**: ").append(f.notes()).append('\n');
            }
            md.append("\n**인식하는 라벨 (").append(f.synonyms().size()).append("개)**\n\n");
            StringJoiner labels = new StringJoiner("` · `", "`", "`");
            f.rawSynonyms().forEach(labels::add);
            md.append(labels).append("\n\n");
        }
        return md.toString();
    }

    /** 값 형식 표기 — 가상 필드는 분리 적재된다는 사실을 함께 드러낸다. */
    private static String typeLabel(Synonyms.FieldSpec f) {
        return f.virtual() ? f.valTyCd() + " (가상 필드 · 분리 적재)" : f.valTyCd();
    }

    /** 라벨 정규화 규칙 — 사전에 라벨을 어떤 형태로 적어도 되는지 설명한다. */
    private static String normalizationRules() {
        return """
                ## 라벨 정규화 규칙

                문서마다 같은 라벨을 다르게 적는다(`고시일자` / `고시 일자` / `1. 고시일자 :`).
                매칭 전에 아래 순서로 라벨을 정규화하므로, **사전에는 읽기 좋은 원문 형태로 적으면 된다.**

                | 순서 | 규칙 | 예 |
                |---:|---|---|
                | 1 | 유니코드 NFC 정규화 | 자모 분리 표기 → 완성형 |
                | 2 | 선행 번호·기호 제거 | `3. 점용·사용의 장소` → `점용·사용의 장소` |
                | 3 | 공백 제거(전각 포함) | `주    소` → `주소` |
                | 4 | 가운뎃점 통일(`ㆍ⋅∙•․‧・･` → `·`) | `점용ㆍ사용 기간` → `점용·사용기간` |
                | 5 | 후행 콜론 제거 | `면적 :` → `면적` |

                괄호 병기 라벨은 분리해 양쪽 모두로 매칭을 시도한다 —
                `성명(상호)`는 `성명`과 `상호` 중 사전에 있는 쪽으로 매핑된다.

                """;
    }

    /** 사전 보강 절차 — 검토자가 실제로 무엇을 하면 되는지. */
    private static String howToExtend() {
        return """
                ## 사전에 라벨 추가하기

                1. `docs/EXTRAS_REVIEW.md`(미매핑 라벨 검토 리포트)에서 빈도가 높은 라벨을 고른다.
                2. 그 라벨이 **기존 표준 필드와 같은 뜻인지** 판단한다.
                   - 같은 뜻이면 → 해당 필드의 `synonyms` 배열에 추가한다.
                   - 다른 뜻인데 여러 지자체에서 반복되면 → 새 표준 컬럼 승격을 검토한다.
                   - 한두 건뿐이면 → extras에 그대로 두고 다음 검토 때 다시 본다.
                3. `src/main/resources/synonyms.json`을 고친다.
                   같은 라벨이 두 필드에 중복 등재되면 **기동 시 오류로 중단**되므로,
                   충돌은 배포 전에 드러난다.
                4. `mvn test`로 회귀를 확인하고, `java -jar extract.jar dict`로 이 문서를 갱신한다.
                5. 파이프라인을 다시 돌리면 기존 문서도 재적재되어(파일 단위 멱등 적재)
                   extras에 있던 값이 표준 컬럼으로 승격된다.
                """;
    }
}
