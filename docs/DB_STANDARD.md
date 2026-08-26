# DB 표준 사전 — 표준단어·표준도메인·표준용어·표준코드

> **자동 생성 문서입니다.** 직접 고치지 마세요.
> 사전 본문은 `src/main/resources/db/standard_terms.json`이며, 고친 뒤
> `java -jar extract.jar dict`를 다시 실행하면 이 문서가 갱신됩니다.

| 항목 | 값 |
|---|---|
| 사전 버전 | `2026-08-22` |
| 표준단어 | 51개 (수식어 33 · 분류어 18) |
| 표준도메인 | 18개 |
| 표준용어 | 테이블 8 · 컬럼 36 |
| 표준코드 | 9군 |

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

## ① 표준단어

### 수식어

| 표준단어 | 영문약어 | 영문명 | 비고 |
|---|---|---|---|
| 기관 | `AGNCY` | AGENCY | — |
| 게시판 | `BBS` | BULLETIN BOARD | — |
| 고시공고 | `NOTI` | NOTICE | — |
| 첨부 | `ATCH` | ATTACHMENT | — |
| 파일 | `FILE` | FILE | — |
| 이미지 | `IMG` | IMAGE | — |
| 항목 | `ITEM` | ITEM | — |
| 라벨 | `LBL` | LABEL | 고시문에 적힌 항목 이름 원문. 표준항목으로 매핑되지 못한 값을 담는 NOTI_LBL_VAL_DTL에만 쓴다 |
| 값 | `VAL` | VALUE | 분류어로는 쓰지 않는다 — 자유 텍스트 값은 CTNT가 받는다 |
| 종류 | `KND` | KIND | — |
| 유형 | `TY` | TYPE | TYPE은 예약어라 TY로 줄인다 |
| 상위 | `HRNK` | HIGH RANK | — |
| 처분 | `DSPS` | DISPOSITION | — |
| 반복 | `RPT` | REPEAT | — |
| 실패 | `FAIL` | FAIL | — |
| 단계 | `STEP` | STEP | — |
| 메시지 | `MSG` | MESSAGE | — |
| 제외 | `EXCL` | EXCLUDE | — |
| 사유 | `RSN` | REASON | — |
| 확장자 | `EXTN` | EXTENSION | — |
| 실제 | `ACTL` | ACTUAL | REAL은 PostgreSQL의 부동소수 타입명이라 쓰지 않는다 |
| 스캔 | `SCAN` | SCAN | — |
| 추출 | `EXTC` | EXTRACT | — |
| 엔진 | `ENGN` | ENGINE | — |
| 상태 | `STTS` | STATUS | — |
| 처리 | `PROC` | PROCESS | — |
| 캡션 | `CPTN` | CAPTION | — |
| 계열 | `SRS` | SERIES | — |
| 주요 | `CORE` | CORE | — |
| 최초 | `FRST` | FIRST | — |
| 최종 | `LAST` | LAST | — |
| 등록 | `REG` | REGISTER | — |
| 변경 | `CHG` | CHANGE | — |

### 분류어 — 이름 끝에 오며 도메인을 지시한다

| 표준단어 | 영문약어 | 도메인 | 물리 타입 | 비고 |
|---|---|---|---|---|
| 일련번호 | `SN` | `D_SN` | `INTEGER` | — |
| 번호 | `NO` | `D_NO` | `VARCHAR(50)` | — |
| 코드 | `CD` | `D_CD` | `VARCHAR(25)` | — |
| 명 | `NM` | `D_NM` | `VARCHAR(300)` | — |
| 제목 | `TTL` | `D_TTL` | `VARCHAR(500)` | — |
| 일자 | `DT` | `D_DT` | `DATE` | — |
| 일시 | `DTM` | `D_DTM` | `TIMESTAMPTZ` | — |
| 여부 | `YN` | `D_YN` | `CHAR(1)` | — |
| 내용 | `CTNT` | `D_CTNT` | `TEXT` | — |
| 경로 | `PATH` | `D_PATH` | `VARCHAR(1000)` | — |
| 건수 | `CNT` | `D_CNT` | `INTEGER` | — |
| 설명 | `DSCR` | `D_DSCR` | `VARCHAR(1000)` | — |
| 금액 | `AMT` | `D_AMT` | `NUMERIC(18,3)` | — |
| 수량 | `QTY` | `D_QTY` | `NUMERIC` | — |
| 율 | `RT` | `D_RT` | `NUMERIC(7,4)` | — |
| 순번 | `SEQ` | `D_SEQ` | `INTEGER` | — |
| 구분 | `SE` | `D_SE` | `VARCHAR(4)` | — |
| 식별자 | `ID` | `D_ID` | `VARCHAR(20)` | — |

## ② 표준도메인

PostgreSQL `CREATE DOMAIN`으로 실제 강제합니다 — 문서로만 두면 다시 어긋납니다.

| 도메인 | 분류어 | 물리 타입 | 제약 | 근거 |
|---|---|---|---|---|
| `D_SN` | 일련번호 | `INTEGER` | — | 숫자 순번·일련번호. 기관·게시물·첨부·이미지·처분·반복 순번이 모두 이 도메인이다 |
| `D_NO` | 번호 | `VARCHAR(50)` | — | 문자 번호. "고시 제2026-47호"처럼 접두어·괄호가 붙은 원문 표기를 그대로 담는다 |
| `D_CD` | 코드 | `VARCHAR(25)` | — | 표준코드 허용값. 공고종류·공고항목은 12자 규칙을 지키므로 폭을 정하는 것은 실패종류코드다 — 최장값이 … |
| `D_NM` | 명 | `VARCHAR(300)` | — | 이름. 첨부파일명이 공고 제목을 그대로 쓰는 경우가 있어 300으로 잡았다 |
| `D_TTL` | 제목 | `VARCHAR(500)` | — | 문서 제목. 고시문 제목은 한 문장을 통째로 쓰는 일이 잦아 명보다 길게 잡는다 |
| `D_DT` | 일자 | `DATE` | — | 날짜만. 시각이 필요하면 일시(DTM)를 쓴다 |
| `D_DTM` | 일시 | `TIMESTAMPTZ` | — | 타임존 포함 일시. 감사 컬럼이 이 도메인이다 |
| `D_YN` | 여부 | `CHAR(1)` | `VALUE IN ('Y','N')` | 'Y'/'N' 두 값만 허용한다. BOOLEAN을 쓰지 않는 이유는 표준 분류어 YN이 지시하는 도메인이 … |
| `D_CTNT` | 내용 | `TEXT` | — | 길이를 예측할 수 없는 자유 텍스트. 실패 메시지·적재제외 사유·항목값이 여기 속한다 |
| `D_PATH` | 경로 | `VARCHAR(1000)` | — | 파일 시스템 절대경로 |
| `D_CNT` | 건수 | `INTEGER` | — | 예비 — 현재 스키마에 쓰이는 컬럼이 없다 |
| `D_DSCR` | 설명 | `VARCHAR(1000)` | — | 예비. 주의: 물리명을 DESC로 줄이면 SQL 예약어와 충돌한다 |
| `D_AMT` | 금액 | `NUMERIC(18,3)` | — | 예비 |
| `D_QTY` | 수량 | `NUMERIC` | — | 예비 |
| `D_RT` | 율 | `NUMERIC(7,4)` | — | 예비 |
| `D_SEQ` | 순번 | `INTEGER` | — | 예비 — 이 스키마의 순번은 전부 일련번호(SN)로 등록돼 있다 |
| `D_SE` | 구분 | `VARCHAR(4)` | — | 예비 — 코드 테이블 없이 두세 값만 가르는 구분값용 |
| `D_ID` | 식별자 | `VARCHAR(20)` | — | 예비 — 사용자·시스템 식별자용 |

## ③ 표준용어 — 테이블

| 유형 접미사 | 의미 | 쓰임 |
|---|---|:-:|
| `_BAS` | 기준 — 기준·마스터. 기관·상품 같은 원장성 테이블 | ● |
| `_DTL` | 명세 — 거래·명세. 발생 건을 행으로 쌓는 테이블 | ● |
| `_TC` | 코드 — 코드 테이블 | ● |
| `_HIS` | 이력 — 변경 이력 | 예비 |
| `_SUM` | 요약 — 배치 집계·요약 | 예비 |
| `_MAP` | 매핑 — N:M 관계 해소 | 예비 |

| 논리명 | 물리명 | PK | 무엇의 단위인가 |
|---|---|---|---|
| 기관 | `AGNCY_BAS` | `AGNCY_SN` | 고시·공고를 수집한 기관 게시판. 입력 폴더 하나가 한 행이다 |
| 고시공고게시물 | `NOTI_BAS` | `NOTI_SN` | 게시물. 크롤링 대상이 첨부파일뿐이라 게시물 자체의 정보는 없고 같은 게시물의 첨부를 묶는 키로만 쓴다 |
| 공고종류 | `NOTI_KND_TC` | `NOTI_KND_CD` | 공고종류 56종. 한 기관이 평균 12종을 발행하므로 기관으로는 종류를 구분할 수 없다 |
| 공고항목 | `NOTI_ITEM_TC` | `NOTI_ITEM_CD` | 표준항목 40종. synonyms.json이 단일 정의처이며 ReferenceSync가 기동 시 upser… |
| 첨부파일 | `ATCH_FILE_DTL` | `NOTI_SN`, `ATCH_SN` | 파일 1건. 문서 단위 메타(고시번호·고시일자·제목)와 추출 상태 |
| 첨부이미지 | `ATCH_IMG_DTL` | `NOTI_SN`, `ATCH_SN`, `IMG_SN` | 이미지는 처분 레코드가 아니라 첨부파일의 속성이다 — 한 파일이 레코드 N건을 낳을 때 어느 레코드에 붙일… |
| 공고항목값 | `NOTI_ITEM_VAL_DTL` | `NOTI_SN`, `ATCH_SN`, `DSPS_SN`, `NOTI_ITEM_CD`, `RPT_SN` | 항목값. 40개 표준항목을 전부 동등하게 행으로 담는다 |
| 고시공고라벨값 | `NOTI_LBL_VAL_DTL` | `NOTI_SN`, `ATCH_SN`, `DSPS_SN`, `ITEM_LBL_NM` | 표준항목으로 매핑되지 못한 값. 라벨 원문을 키로 담는다 — NOTI_ITEM_TC로 가는 FK가 없어야 … |

### 테이블별 구성 컬럼

**기관** `AGNCY_BAS`

| 논리명 | 물리명 | 도메인 | PK |
|---|---|---|:-:|
| 기관일련번호 | `AGNCY_SN` | `D_SN` | ● |
| 기관명 | `AGNCY_NM` | `D_NM` |  |
| 기관종류코드 | `AGNCY_KND_CD` | `D_CD` |  |
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |  |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |  |

**고시공고게시물** `NOTI_BAS`

| 논리명 | 물리명 | 도메인 | PK |
|---|---|---|:-:|
| 고시공고일련번호 | `NOTI_SN` | `D_SN` | ● |
| 기관일련번호 | `AGNCY_SN` | `D_SN` |  |
| 게시상태코드 | `BBS_STTS_CD` | `D_CD` |  |
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |  |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |  |

**공고종류** `NOTI_KND_TC`

| 논리명 | 물리명 | 도메인 | PK |
|---|---|---|:-:|
| 공고종류코드 | `NOTI_KND_CD` | `D_CD` | ● |
| 공고종류명 | `NOTI_KND_NM` | `D_NM` |  |
| 상위공고종류명 | `HRNK_NOTI_KND_NM` | `D_NM` |  |
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |  |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |  |

**공고항목** `NOTI_ITEM_TC`

| 논리명 | 물리명 | 도메인 | PK |
|---|---|---|:-:|
| 공고항목코드 | `NOTI_ITEM_CD` | `D_CD` | ● |
| 공고항목명 | `NOTI_ITEM_NM` | `D_NM` |  |
| 항목계열명 | `ITEM_SRS_NM` | `D_NM` |  |
| 항목값유형코드 | `ITEM_VAL_TY_CD` | `D_CD` |  |
| 주요항목여부 | `CORE_ITEM_YN` | `D_YN` |  |
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |  |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |  |

**첨부파일** `ATCH_FILE_DTL`

| 논리명 | 물리명 | 도메인 | PK |
|---|---|---|:-:|
| 고시공고일련번호 | `NOTI_SN` | `D_SN` | ● |
| 첨부일련번호 | `ATCH_SN` | `D_SN` | ● |
| 첨부파일명 | `ATCH_FILE_NM` | `D_NM` |  |
| 처리상태코드 | `PROC_STTS_CD` | `D_CD` |  |
| 실패단계코드 | `FAIL_STEP_CD` | `D_CD` |  |
| 실패종류코드 | `FAIL_KND_CD` | `D_CD` |  |
| 실패메시지내용 | `FAIL_MSG_CTNT` | `D_CTNT` |  |
| 적재제외사유내용 | `EXCL_RSN_CTNT` | `D_CTNT` |  |
| 파일확장자명 | `FILE_EXTN_NM` | `D_NM` |  |
| 실제파일확장자명 | `ACTL_FILE_EXTN_NM` | `D_NM` |  |
| 스캔여부 | `SCAN_YN` | `D_YN` |  |
| 추출엔진명 | `EXTC_ENGN_NM` | `D_NM` |  |
| 공고종류코드 | `NOTI_KND_CD` | `D_CD` |  |
| 고시번호 | `NOTI_NO` | `D_NO` |  |
| 고시일자 | `NOTI_DT` | `D_DT` |  |
| 고시제목 | `NOTI_TTL` | `D_TTL` |  |
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |  |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |  |

**첨부이미지** `ATCH_IMG_DTL`

| 논리명 | 물리명 | 도메인 | PK |
|---|---|---|:-:|
| 고시공고일련번호 | `NOTI_SN` | `D_SN` | ● |
| 첨부일련번호 | `ATCH_SN` | `D_SN` | ● |
| 이미지일련번호 | `IMG_SN` | `D_SN` | ● |
| 이미지캡션내용 | `IMG_CPTN_CTNT` | `D_CTNT` |  |
| 이미지파일경로 | `IMG_FILE_PATH` | `D_PATH` |  |
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |  |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |  |

**공고항목값** `NOTI_ITEM_VAL_DTL`

| 논리명 | 물리명 | 도메인 | PK |
|---|---|---|:-:|
| 고시공고일련번호 | `NOTI_SN` | `D_SN` | ● |
| 첨부일련번호 | `ATCH_SN` | `D_SN` | ● |
| 처분일련번호 | `DSPS_SN` | `D_SN` | ● |
| 공고항목코드 | `NOTI_ITEM_CD` | `D_CD` | ● |
| 반복일련번호 | `RPT_SN` | `D_SN` | ● |
| 항목값내용 | `ITEM_VAL_CTNT` | `D_CTNT` |  |
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |  |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |  |

**고시공고라벨값** `NOTI_LBL_VAL_DTL`

| 논리명 | 물리명 | 도메인 | PK |
|---|---|---|:-:|
| 고시공고일련번호 | `NOTI_SN` | `D_SN` | ● |
| 첨부일련번호 | `ATCH_SN` | `D_SN` | ● |
| 처분일련번호 | `DSPS_SN` | `D_SN` | ● |
| 항목라벨명 | `ITEM_LBL_NM` | `D_NM` | ● |
| 항목값내용 | `ITEM_VAL_CTNT` | `D_CTNT` |  |
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |  |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |  |

## ③ 표준용어 — 컬럼

논리명과 물리명은 1:1로 대응합니다. 이 매핑이 깨지면 표준 위반입니다.

| 논리명 | 물리명 | 조합 | 도메인 | 코드 | 설명 |
|---|---|---|---|---|---|
| 기관일련번호 | `AGNCY_SN` | 기관 + 일련번호 | `D_SN` | — | 입력 폴더명 앞 번호. AgencyRegistry가 폴더 집합 전체를 보고 결정론적으로 발급한다 |
| 기관명 | `AGNCY_NM` | 기관 + 명 | `D_NM` | — | 수집처 기관명 — 입력 폴더명에서 온다 |
| 기관종류코드 | `AGNCY_KND_CD` | 기관 + 종류 + 코드 | `D_CD` | `CD_AGNCY_KND` | GZT는 기관 종류가 아니라 수집 경로다 — 같은 부처를 자체 게시판과 전자관보 양쪽에서 긁으므로 이름이 … |
| 고시공고일련번호 | `NOTI_SN` | 고시공고 + 일련번호 | `D_SN` | — | 크롤 일련번호. 첨부파일명 앞자리다. 문서 본문의 고시번호(NOTI_NO)와는 다른 것이다 |
| 게시상태코드 | `BBS_STTS_CD` | 게시판 + 상태 + 코드 | `D_CD` | `CD_BBS_STTS` | 폴더명 꼬리표로 가른다. 같은 기관이 게시판을 둘 운영하면 기관은 하나고 이 값만 갈린다 |
| 공고종류코드 | `NOTI_KND_CD` | 고시공고 + 종류 + 코드 | `D_CD` | `CD_NOTI_KND` | {상위분류}_{행위} 조합. 코드만 보고도 계열을 알 수 있어야 필터가 쉽다 |
| 공고종류명 | `NOTI_KND_NM` | 고시공고 + 종류 + 명 | `D_NM` | — | 공고종류의 한글 이름 |
| 상위공고종류명 | `HRNK_NOTI_KND_NM` | 상위 + 고시공고 + 종류 + 명 | `D_NM` | — | 점용·사용 / 실시계획 / 매립 / 점용료·사용료 / 혼합 / 기타. UPPER_로 시작하면 SQL 함수명… |
| 공고항목코드 | `NOTI_ITEM_CD` | 고시공고 + 항목 + 코드 | `D_CD` | `CD_NOTI_ITEM` | 표준항목코드 = synonyms.json의 canonical |
| 공고항목명 | `NOTI_ITEM_NM` | 고시공고 + 항목 + 명 | `D_NM` | — | 표준항목의 한글 표시명 |
| 항목계열명 | `ITEM_SRS_NM` | 항목 + 계열 + 명 | `D_NM` | — | 같은 뜻을 문맥별로 다르게 부르는 항목들의 묶음. 누락 검증은 항목이 아니라 계열 단위로 봐야 오탐이 없다 |
| 항목값유형코드 | `ITEM_VAL_TY_CD` | 항목 + 값 + 유형 + 코드 | `D_CD` | `CD_ITEM_VAL_TY` | 값의 정규화 방식을 정한다 |
| 주요항목여부 | `CORE_ITEM_YN` | 주요 + 항목 + 여부 | `D_YN` | — | 전수 표본 출현율 60% 이상인 주요 6항목 — 누락 검증 가중치용이며 저장 위치와는 무관하다 |
| 첨부일련번호 | `ATCH_SN` | 첨부 + 일련번호 | `D_SN` | — | 첨부가 1건인 게시물은 파일명에 순번이 없어 항상 1이다. 결번은 버그가 아니라 미수집 신호이므로 다시 매… |
| 첨부파일명 | `ATCH_FILE_NM` | 첨부 + 파일 + 명 | `D_NM` | — | 입력 파일명 원문 |
| 처리상태코드 | `PROC_STTS_CD` | 처리 + 상태 + 코드 | `D_CD` | `CD_PROC_STTS` | FAIL·SKIP도 행으로 남긴다 — 성공만 적재하면 "추출 0건인 기관"이 DB에서 보이지 않는다 |
| 실패단계코드 | `FAIL_STEP_CD` | 실패 + 단계 + 코드 | `D_CD` | `CD_FAIL_STEP` | 파이프라인의 어느 단계에서 넘어졌는지 |
| 실패종류코드 | `FAIL_KND_CD` | 실패 + 종류 + 코드 | `D_CD` | `CD_FAIL_KND` | 실패 이유의 갈래. 대응이 다른 실패를 한 덩어리로 세지 않기 위한 축이다 |
| 실패메시지내용 | `FAIL_MSG_CTNT` | 실패 + 메시지 + 내용 | `D_CTNT` | — | 사람이 읽는 실패 설명 원문 |
| 적재제외사유내용 | `EXCL_RSN_CTNT` | 제외 + 사유 + 내용 | `D_CTNT` | — | 안내문류 차단 등 적재를 건너뛴 사유 |
| 파일확장자명 | `FILE_EXTN_NM` | 파일 + 확장자 + 명 | `D_NM` | `CD_FILE_EXTN` | 파일명 확장자 기준 형식 |
| 실제파일확장자명 | `ACTL_FILE_EXTN_NM` | 실제 + 파일 + 확장자 + 명 | `D_NM` | `CD_FILE_EXTN` | 매직바이트로 판정한 실제 형식. FILE_EXTN_NM과 다른 행이 곧 확장자가 어긋난 파일 목록이다 |
| 스캔여부 | `SCAN_YN` | 스캔 + 여부 | `D_YN` | — | 본문이 텍스트가 아니라 이미지인 스캔본 여부 |
| 추출엔진명 | `EXTC_ENGN_NM` | 추출 + 엔진 + 명 | `D_NM` | — | 본문을 읽어 낸 엔진 |
| 고시번호 | `NOTI_NO` | 고시공고 + 번호 | `D_NO` | — | 문서 본문의 고시번호("고시 제2026-47호"). 게시물 일련번호와도, 처분받은 허가번호와도 다른 것이다 |
| 고시일자 | `NOTI_DT` | 고시공고 + 일자 | `D_DT` | — | 고시문에 적힌 고시 날짜 |
| 고시제목 | `NOTI_TTL` | 고시공고 + 제목 | `D_TTL` | — | 고시문 제목. 공고종류 판정의 입력이다 |
| 이미지일련번호 | `IMG_SN` | 이미지 + 일련번호 | `D_SN` | — | 한 첨부 안에서의 이미지 순번 |
| 이미지캡션내용 | `IMG_CPTN_CTNT` | 이미지 + 캡션 + 내용 | `D_CTNT` | — | 그림 아래 캡션 원문 |
| 이미지파일경로 | `IMG_FILE_PATH` | 이미지 + 파일 + 경로 | `D_PATH` | — | 추출한 이미지를 저장한 절대경로 |
| 처분일련번호 | `DSPS_SN` | 처분 + 일련번호 | `D_SN` | — | 목록표의 N번째 행 = 처분 1건. 이 값이 없으면 목록표에서 장소·면적·성명이 짝을 잃고 그 손실은 조용… |
| 반복일련번호 | `RPT_SN` | 반복 + 일련번호 | `D_SN` | — | 같은 처분에 같은 항목이 반복될 때의 순번 — 변경 고시문의 당초/변경 대비표가 대표적이다 |
| 항목라벨명 | `ITEM_LBL_NM` | 항목 + 라벨 + 명 | `D_NM` | — | 표준항목으로 매핑되지 못한 라벨 원문. 이 값은 사전이 정하는 코드가 아니라 문서가 적어 온 이름이므로 표… |
| 항목값내용 | `ITEM_VAL_CTNT` | 항목 + 값 + 내용 | `D_CTNT` | — | Java가 정규화한 값. 날짜는 ISO, 기간은 daterange 리터럴, 정규화 실패나 TEXT 항목은 … |
| 최초등록일시 | `FRST_REG_DTM` | 최초 + 등록 + 일시 | `D_DTM` | — | 행을 처음 적재한 시각 |
| 최종변경일시 | `LAST_CHG_DTM` | 최종 + 변경 + 일시 | `D_DTM` | — | 행을 마지막으로 갱신한 시각 |

## ④ 표준코드

### 기관종류코드 `CD_AGNCY_KND`

적용 컬럼: `AGNCY_BAS.AGNCY_KND_CD`

수집처의 갈래. GZT만은 기관 종류가 아니라 수집 경로다

| 코드 | 뜻 |
|---|---|
| `MOF` | 지방해양수산청 |
| `LOCL` | 지방자치단체 |
| `CNTL` | 중앙행정기관 |
| `GZT` | 전자관보 |

### 게시상태코드 `CD_BBS_STTS`

적용 컬럼: `NOTI_BAS.BBS_STTS_CD`

입력 폴더명 꼬리표로 판정한다

| 코드 | 뜻 |
|---|---|
| `POST` | 게시중 |
| `CLSD` | 게시완료 |

### 처리상태코드 `CD_PROC_STTS`

적용 컬럼: `ATCH_FILE_DTL.PROC_STTS_CD`

첨부파일 1건의 처리 결과

| 코드 | 뜻 |
|---|---|
| `OK` | 정상 |
| `FAIL` | 실패 |
| `SKIP` | 적재제외 |

### 실패단계코드 `CD_FAIL_STEP`

적용 컬럼: `ATCH_FILE_DTL.FAIL_STEP_CD`

파이프라인 단계. 콘솔에는 한글 표시명이 나가고 DB에는 코드가 들어간다

| 코드 | 뜻 |
|---|---|
| `DTCT` | 판별 |
| `EXTC` | 추출 |
| `TBIT` | 표해석 |
| `MAPP` | 매핑 |
| `SAVE` | 저장 |

### 실패종류코드 `CD_FAIL_KND`

적용 컬럼: `ATCH_FILE_DTL.FAIL_KND_CD`

detect.FailureKind가 정의처다. 이미 대문자 영문이라 값을 그대로 등재한다

| 코드 | 뜻 |
|---|---|
| `HWP3_LEGACY` | 한글 3.0 구조 미해독 |
| `NOT_COMPOUND_FILE` | HWP 컨테이너 아님 |
| `PASSWORD_PROTECTED` | 열기암호 설정 |
| `DRM_PROTECTED` | DRM 보안문서 |
| `DISTRIBUTION_UNSUPPORTED` | 배포용 본문 미해독 |
| `DISTRIBUTION_TRUNCATED` | 배포용 본문 잘림 |
| `ENCRYPTED` | 암호화 갈래 미상 |
| `NOT_ZIP` | HWPX 컨테이너 아님 |
| `ZIP_CORRUPT` | ZIP 구조 손상 |
| `XML_PARSE` | 본문 XML 파싱 실패 |
| `DOCUMENT_PARSE` | 내부 문서구조 미해독 |
| `PDF_LOAD` | PDF 열기 실패 |
| `EMPTY_FILE` | 빈 파일 |
| `FILE_ACCESS` | 파일 접근 실패 |
| `UNSUPPORTED_EXT` | 미지원 확장자 |
| `OUT_OF_MEMORY` | 메모리 부족 |
| `IO` | 입출력 오류 |
| `OTHER` | 기타 |

### 항목값유형코드 `CD_ITEM_VAL_TY`

적용 컬럼: `NOTI_ITEM_TC.ITEM_VAL_TY_CD`

synonyms.json의 value_type이 정의처다

| 코드 | 뜻 |
|---|---|
| `TEXT` | 문자 — 원문 보존 |
| `DATE` | 일자 — ISO 정규화 |
| `DTRG` | 기간 — daterange 리터럴 |
| `NUM` | 수치 |

### 파일확장자명 `CD_FILE_EXTN`

적용 컬럼: `ATCH_FILE_DTL.FILE_EXTN_NM`, `ATCH_FILE_DTL.ACTL_FILE_EXTN_NM` — **강제하지 않는 참고 목록**

참고 목록이다. 파일명 확장자는 열린 집합이라(jpg·zip·xlsx가 실제로 들어온다) 값을 강제하지 않는다. 아래는 detect.DocFormat이 판정하는 값들이다

| 코드 | 뜻 |
|---|---|
| `hwp` | 한글 5.0 (OLE 복합문서) |
| `hwp3` | 한글 3.0 |
| `hwpx` | 한글 XML (OWPML/ZIP) |
| `hml` | 한글 마크업 |
| `pdf` | PDF |

### 공고종류코드 `CD_NOTI_KND`

적용 컬럼: `NOTI_KND_TC.NOTI_KND_CD`, `ATCH_FILE_DTL.NOTI_KND_CD`

56종. 값 목록은 notice_types.json이 정의처이고 ReferenceSync가 테이블로 동기화한다 — 여기에 복사해 두면 두 곳이 어긋난다

> 값 목록의 정의처는 `notice_types.json`이고 `NOTI_KND_TC` 테이블로 동기화됩니다 — 여기에 복사해 두면 두 곳이 어긋납니다.

### 공고항목코드 `CD_NOTI_ITEM`

적용 컬럼: `NOTI_ITEM_TC.NOTI_ITEM_CD`, `NOTI_ITEM_VAL_DTL.NOTI_ITEM_CD`

표준항목 40종(+ 컬럼으로 직행하는 attachment 5종). 값 목록은 synonyms.json이 정의처다. 컬럼명이 아니라 행으로 쌓이는 코드 데이터이므로 표준단어로 분해될 필요가 없다

> 값 목록의 정의처는 `synonyms.json`이고 `NOTI_ITEM_TC` 테이블로 동기화됩니다 — 여기에 복사해 두면 두 곳이 어긋납니다.

