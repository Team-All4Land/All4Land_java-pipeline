package com.onnara.extract.docs;

import com.onnara.extract.common.DbStandard;

import java.util.List;

/**
 * DB 표준 사전을 검토용 Markdown 문서로 렌더링한다.
 *
 * <p>사전 본문은 {@code src/main/resources/db/standard_terms.json}이고 이 문서는 그 파생물이다.
 * 사전을 고칠 때마다 문서를 손으로 맞추면 어긋나므로, 문서는 항상 생성해서 쓴다.
 */
public final class DbStandardDoc {

    /** 인스턴스화 방지 — 정적 렌더링 함수만 제공하는 유틸리티 클래스. */
    private DbStandardDoc() {
    }

    /** 사전 전체를 Markdown 문서 문자열로 렌더링한다. */
    public static String render() {
        StringBuilder md = new StringBuilder();
        md.append("# DB 표준 사전 — 표준단어·표준도메인·표준용어·표준코드\n\n");
        md.append("> **자동 생성 문서입니다.** 직접 고치지 마세요.\n")
                .append("> 사전 본문은 `src/main/resources/db/standard_terms.json`이며, 고친 뒤\n")
                .append("> `java -jar extract.jar dict`를 다시 실행하면 이 문서가 갱신됩니다.\n\n");

        md.append("| 항목 | 값 |\n|---|---|\n");
        md.append("| 사전 버전 | `").append(DbStandard.version()).append("` |\n");
        md.append("| 표준단어 | ").append(DbStandard.words().size()).append("개 (수식어 ")
                .append(DbStandard.words().stream().filter(w -> !w.isClassifier()).count())
                .append(" · 분류어 ")
                .append(DbStandard.words().stream().filter(DbStandard.Word::isClassifier).count())
                .append(") |\n");
        md.append("| 표준도메인 | ").append(DbStandard.domains().size()).append("개 |\n");
        md.append("| 표준용어 | 테이블 ").append(DbStandard.tables().size())
                .append(" · 컬럼 ").append(DbStandard.terms().size()).append(" |\n");
        md.append("| 표준코드 | ").append(DbStandard.codes().size()).append("군 |\n\n");

        md.append(howItComposes());
        md.append(words());
        md.append(domains());
        md.append(tables());
        md.append(terms());
        md.append(codes());
        return md.toString();
    }

    /** 이름이 만들어지는 4단 구조를 먼저 보인다 — 표를 읽기 전에 규칙을 알아야 한다. */
    private static String howItComposes() {
        return """
                ## 이름은 이렇게 만들어집니다

                ```
                [수식어] + [수식어] + … + [분류어]
                 실제      파일      확장자   명
                 ACTL   +  FILE   +  EXTN  +  NM     →  ACTL_FILE_EXTN_NM
                ```

                **마지막 낱말(분류어)이 반드시 도메인을 지시합니다.** 분류어만 보고도 타입과
                길이를 알 수 있어야 하므로, 분류어 없이 끝나는 이름(`FAIL_STEP`, `IMG_CPTN`)은
                표준 위반입니다.

                테이블은 접두사 없이 `[의미] + [유형 접미사]`입니다. 업무영역 접두사는 두지
                않습니다 — 이 저장소가 단일 업무영역이라 모든 테이블에 같은 접두사가 붙어
                아무것도 가르지 못합니다.

                """;
    }

    /** 표준단어 — 수식어와 분류어를 갈라 싣는다. 분류어는 도메인을 함께 보여야 쓸모가 있다. */
    private static String words() {
        StringBuilder md = new StringBuilder("## ① 표준단어\n\n");

        md.append("### 수식어\n\n");
        md.append("| 표준단어 | 영문약어 | 영문명 | 비고 |\n|---|---|---|---|\n");
        for (DbStandard.Word w : DbStandard.words()) {
            if (w.isClassifier()) {
                continue;
            }
            md.append("| ").append(w.term())
                    .append(" | `").append(w.abbr())
                    .append("` | ").append(w.english())
                    .append(" | ").append(Markdown.cell(w.notes()))
                    .append(" |\n");
        }

        md.append("\n### 분류어 — 이름 끝에 오며 도메인을 지시한다\n\n");
        md.append("| 표준단어 | 영문약어 | 도메인 | 물리 타입 | 비고 |\n|---|---|---|---|---|\n");
        for (DbStandard.Word w : DbStandard.words()) {
            if (!w.isClassifier()) {
                continue;
            }
            String type = DbStandard.domain(w.domain())
                    .map(DbStandard.Domain::sqlType).orElse("—");
            md.append("| ").append(w.term())
                    .append(" | `").append(w.abbr())
                    .append("` | `").append(w.domain())
                    .append("` | `").append(type)
                    .append("` | ").append(Markdown.cell(w.notes()))
                    .append(" |\n");
        }
        return md.append("\n").toString();
    }

    /** 표준도메인 — 근거까지 싣는다. 길이를 왜 그렇게 잡았는지가 다음 개정의 판단 근거다. */
    private static String domains() {
        StringBuilder md = new StringBuilder("## ② 표준도메인\n\n");
        md.append("PostgreSQL `CREATE DOMAIN`으로 실제 강제합니다 — 문서로만 두면 다시 어긋납니다.\n\n");
        md.append("| 도메인 | 분류어 | 물리 타입 | 제약 | 근거 |\n|---|---|---|---|---|\n");
        for (DbStandard.Domain d : DbStandard.domains()) {
            md.append("| `").append(d.id())
                    .append("` | ").append(d.term())
                    .append(" | `").append(d.sqlType())
                    .append("` | ").append(d.check() == null ? "—" : "`" + d.check() + "`")
                    .append(" | ").append(Markdown.cell(d.description()))
                    .append(" |\n");
        }
        return md.append("\n").toString();
    }

    /** 표준용어(테이블) + 유형 접미사. */
    private static String tables() {
        StringBuilder md = new StringBuilder("## ③ 표준용어 — 테이블\n\n");
        md.append("| 유형 접미사 | 의미 | 쓰임 |\n|---|---|:-:|\n");
        for (DbStandard.TableSuffix s : DbStandard.tableSuffixes()) {
            md.append("| `_").append(s.suffix())
                    .append("` | ").append(s.term()).append(" — ").append(Markdown.cell(s.description()))
                    .append(" | ").append(s.used() ? "●" : "예비")
                    .append(" |\n");
        }

        md.append("\n| 논리명 | 물리명 | PK | 무엇의 단위인가 |\n|---|---|---|---|\n");
        for (DbStandard.TableSpec t : DbStandard.tables()) {
            md.append("| ").append(t.logical())
                    .append(" | `").append(t.physical())
                    .append("` | `").append(String.join("`, `", t.primaryKey()))
                    .append("` | ").append(Markdown.cell(t.description()))
                    .append(" |\n");
        }

        md.append("\n### 테이블별 구성 컬럼\n\n");
        for (DbStandard.TableSpec t : DbStandard.tables()) {
            md.append("**").append(t.logical()).append("** `").append(t.physical()).append("`\n\n");
            md.append("| 논리명 | 물리명 | 도메인 | PK |\n|---|---|---|:-:|\n");
            for (String column : t.columns()) {
                DbStandard.TermSpec term = DbStandard.term(column).orElseThrow();
                md.append("| ").append(term.logical())
                        .append(" | `").append(term.physical())
                        .append("` | `").append(term.domain())
                        .append("` | ").append(t.primaryKey().contains(column) ? "●" : "")
                        .append(" |\n");
            }
            md.append("\n");
        }
        return md.toString();
    }

    /** 표준용어(컬럼) 전체 — 논리명 ↔ 물리명 1:1 대응표. */
    private static String terms() {
        StringBuilder md = new StringBuilder("## ③ 표준용어 — 컬럼\n\n");
        md.append("논리명과 물리명은 1:1로 대응합니다. 이 매핑이 깨지면 표준 위반입니다.\n\n");
        md.append("| 논리명 | 물리명 | 조합 | 도메인 | 코드 | 설명 |\n|---|---|---|---|---|---|\n");
        for (DbStandard.TermSpec t : DbStandard.terms()) {
            List<DbStandard.Word> parsed = DbStandard.decompose(t.physical());
            StringBuilder composition = new StringBuilder();
            for (int i = 0; i < parsed.size(); i++) {
                composition.append(i == 0 ? "" : " + ").append(parsed.get(i).term());
            }
            md.append("| ").append(t.logical())
                    .append(" | `").append(t.physical())
                    .append("` | ").append(composition)
                    .append(" | `").append(t.domain())
                    .append("` | ").append(t.code() == null ? "—" : "`" + t.code() + "`")
                    .append(" | ").append(Markdown.cell(t.description()))
                    .append(" |\n");
        }
        return md.append("\n").toString();
    }

    /** 표준코드 — 코드성 속성의 허용값 집합. */
    private static String codes() {
        StringBuilder md = new StringBuilder("## ④ 표준코드\n\n");
        for (DbStandard.CodeGroup c : DbStandard.codes()) {
            md.append("### ").append(c.name()).append(" `").append(c.group()).append("`\n\n");
            md.append("적용 컬럼: `").append(String.join("`, `", c.columns())).append("`");
            if (!c.enforced()) {
                md.append(" — **강제하지 않는 참고 목록**");
            }
            md.append("\n\n");
            if (!c.description().isBlank()) {
                md.append(c.description()).append("\n\n");
            }
            if (c.values().isEmpty()) {
                md.append("> 값 목록의 정의처는 `").append(c.managedBy())
                        .append("`이고 `").append(c.table())
                        .append("` 테이블로 동기화됩니다 — 여기에 복사해 두면 두 곳이 어긋납니다.\n\n");
                continue;
            }
            md.append("| 코드 | 뜻 |\n|---|---|\n");
            for (DbStandard.CodeValue v : c.values()) {
                md.append("| `").append(v.code()).append("` | ").append(v.name()).append(" |\n");
            }
            md.append("\n");
        }
        return md.toString();
    }
}
