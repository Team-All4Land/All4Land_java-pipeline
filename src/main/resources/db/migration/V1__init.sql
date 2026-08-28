-- 공유수면 고시공고 스키마 — 표준도메인 20 + 9 엔터티.
--
-- 이름은 DB 표준 사전(src/main/resources/db/standard_terms.json)이 정한다. 사전을 고치지 않고
-- 여기에 컬럼을 보태면 DbStandardTest가 막는다 — 사전과 이 파일을 테이블 단위로 대조한다.
--
-- 사전이 따르는 상위 근거는 이 사업의 데이터 표준화 지침(OFBD-2210-01)과 데이터 표준 관리체계
-- 정의서(OFBD-3210-02)이고, 그 둘은 행정안전부 공공기관의 데이터베이스 표준화 지침과
-- 행정표준용어사전을 근거로 삼는다.
--
-- 계층: 기관 → 게시물 → 첨부파일 → (처분 레코드) 항목값
--   OS_INSTT_BAS          기관 게시판. 크롤러가 발급한 기관번호가 정의처다
--   OS_NOTI_BAS           게시물 1건. 크롤러 게시물 목록 엑셀 한 줄이 한 행이다
--   OS_CRWL_LOG_DTL       하루치 수집에서 기관 하나를 긁은 결과
--   OS_NOTI_KND_TC        공고종류 56종
--   OS_NOTI_ITEM_TC       공고항목 40종. notice_items.json이 정의처이고 ReferenceSync가 동기화한다
--   OS_ATCH_FILE_DTL      파일 1건. 원본 절대경로, 문서 단위 메타(고시번호·고시일자·제목)와 추출 상태
--   OS_ATCH_IMG_DTL       첨부에 딸린 이미지
--   OS_NOTI_ITEM_VAL_DTL  항목값. 40개 표준항목을 전부 동등하게 행으로 담는다
--   OS_NOTI_LBL_VAL_DTL   표준항목으로 매핑되지 못한 값. 라벨 원문을 키로 담는다
--
-- 이름 규칙 세 가지만 여기 적어 둔다(나머지는 docs/DB_STANDARD.md):
--   ① 물리명은 [수식어]+…+[분류어]이고 마지막 낱말이 도메인을 지시한다. 분류어만 보고 타입을
--      알 수 있어야 하므로 분류어 없이 끝나는 이름(…_STEP, …_CPTN)은 쓰지 않는다.
--   ② 테이블은 [업무코드]_[테이블의미]다(OFBD-2210-01 §3.2.3). 업무코드 OS는 해양공간이고,
--      크롤러 DB의 OS_PUBLIC_WATERS_NOTICE도 같은 코드를 쓴다. 의미 뒤의 유형 접미사는
--      기준 _BAS, 명세 _DTL, 코드 _TC다. 코드 테이블이 _CD가 아닌 이유는 그러면 테이블
--      OS_NOTI_KND_CD와 그 안의 컬럼 NOTI_KND_CD가 같은 이름이 되기 때문이다.
--   ③ 제약조건·인덱스는 [테이블명]을 앞에 두고 유형과 두 자리 일련번호를 뒤에 붙인다 —
--      OS_NOTI_BAS_PK, OS_NOTI_BAS_FK01, OS_NOTI_BAS_UK01, OS_NOTI_BAS_IX01. 일련번호는
--      이름만 봐서 무엇을 위한 것인지 알려 주지 못하므로 COMMENT ON INDEX로 그 자리를 메운다.
--   ④ 감사 컬럼 FRST_REG_DTM·LAST_CHG_DTM은 전 테이블에 같은 이름으로 둔다. 등록자 ID는 두지
--      않는다 — 사람이 아니라 배치 CLI가 적재하므로 무엇을 넣어도 가짜다. 예외는 쌓기만 하고
--      고치지 않는 로그(OS_CRWL_LOG_DTL)뿐인데, 갱신이 없어 최종변경일시가 최초등록일시와
--      영원히 같기 때문이다. 면제는 표준 사전이 audit:false로 선언하고 DbStandardTest가
--      양쪽으로 대조한다 — 선언해 놓고 컬럼을 남겨 둬도 붉게 난다.
--
-- 식별자는 대문자로 적지만 PostgreSQL이 따옴표 없는 식별자를 소문자로 접으므로 실제 저장은
-- os_instt_bas·instt_sn이다. 큰따옴표로 대문자를 강제하지 않는다 — 한 번 강제하면 이후 모든
-- 질의가 영원히 따옴표를 달아야 하고, 하나만 빠뜨려도 "relation does not exist"로 죽는다.
--
-- IF NOT EXISTS를 쓰지 않는다. PostgreSQL에 CREATE DOMAIN IF NOT EXISTS가 없어 어차피 절반만
-- 방어하게 되고, init 마이그레이션은 Flyway가 빈 스키마에 한 번만 돌린다(실패하면 트랜잭션
-- 통째로 롤백된다). 객체가 남아 있다면 스키마를 안 지운 것이므로, 조용히 건너뛰는 것보다
-- 죽는 편이 낫다. 다시 세우는 절차는 README "개발 DB 초기화"에 있다.
--
-- 파이프라인 앞에 크롤러가 붙는다. 크롤러는 게시물 목록 엑셀과 첨부파일 폴더 두 가지를 내놓고,
-- 엑셀이 OS_NOTI_BAS·OS_CRWL_LOG_DTL을, 첨부파일 폴더가 OS_ATCH_FILE_DTL 이하를 채운다. 엑셀의 번호가
-- 곧 NOTI_SN이며 실행마다 1로 되돌아가지 않고 마지막 번호 뒤로 이어진다.
--
-- 크롤러가 준 컬럼에 NOT NULL을 걸지 않는다. 크롤 산출물이 아닌 파일(수동 수집분, samples/)은
-- 음수 NOTI_SN을 발급받아 같은 테이블에 들어오는데, 그 행에는 원문키도 URL도 없다. NOT NULL을
-- 걸면 수동 수집분 적재가 통째로 막히므로, 중복은 SRC_KEY_HASH의 UNIQUE로만 막는다 —
-- PostgreSQL의 UNIQUE는 NULL을 서로 다른 값으로 보므로 음수 행이 여럿 공존한다.
--
-- 값 컬럼을 두지 않고 전부 EAV로 담는 이유:
--   ① 종류마다 등장하는 항목 집합이 다르고 극단적으로 성기다. 전역 출현율이 60%를 넘는
--      항목은 6개뿐이고 최하위는 0.01%다. 40컬럼으로 펴면 대부분 NULL이 된다.
--   ② 항목 레지스트리 자체가 분석 회차마다 바뀐다(08.06판 43항목 → 08.07판 40항목).
--      컬럼으로 박으면 그때마다 마이그레이션을 다시 짜야 한다.
--   ③ "종류별 항목 출현율" 같은 집계가 40컬럼 UNION이 아니라 GROUP BY 한 줄이 된다.

-- ---------------------------------------------------------------------------
-- 1. 표준도메인
-- ---------------------------------------------------------------------------
-- 분류어 하나가 도메인 하나를 지시한다. 지금 쓰지 않는 분류어까지 만들어 두는 이유는,
-- 다음 컬럼을 보탤 때 타입을 새로 고민하는 순간 표준이 무너지기 때문이다.
CREATE DOMAIN D_SN   AS INTEGER;
CREATE DOMAIN D_NO   AS VARCHAR(50);
CREATE DOMAIN D_CD   AS VARCHAR(25);
CREATE DOMAIN D_NM   AS VARCHAR(300);
CREATE DOMAIN D_TTL  AS VARCHAR(500);
CREATE DOMAIN D_DT   AS DATE;
CREATE DOMAIN D_DTM  AS TIMESTAMPTZ;
CREATE DOMAIN D_YN   AS CHAR(1) CHECK (VALUE IN ('Y', 'N'));
CREATE DOMAIN D_CTNT AS TEXT;
CREATE DOMAIN D_PATH AS VARCHAR(1000);
CREATE DOMAIN D_CNT  AS INTEGER;
CREATE DOMAIN D_DSCR AS VARCHAR(1000);
CREATE DOMAIN D_AMT  AS NUMERIC(18,3);
CREATE DOMAIN D_QTY  AS NUMERIC;
CREATE DOMAIN D_RT   AS NUMERIC(7,4);
CREATE DOMAIN D_SEQ  AS INTEGER;
CREATE DOMAIN D_SE   AS VARCHAR(4);
CREATE DOMAIN D_ID   AS VARCHAR(20);
CREATE DOMAIN D_HASH AS CHAR(64) CHECK (VALUE ~ '^[0-9a-f]{64}$');
CREATE DOMAIN D_URL  AS VARCHAR(2000);

COMMENT ON DOMAIN D_SN   IS '일련번호 — 숫자 순번. 기관·게시물·첨부·이미지·처분·반복이 모두 이 도메인이다';
COMMENT ON DOMAIN D_NO   IS '번호 — 문자 번호. "고시 제2026-47호"처럼 접두어가 붙은 원문 표기를 담는다';
COMMENT ON DOMAIN D_CD   IS '코드 — 코드값은 12자를 넘지 않는다(행안부 공통표준도메인 코드C12). 도메인이 25자인 것은 그 규칙을 따르지 않는 실패종류코드 때문이다 — FailureKind의 최장값이 DISTRIBUTION_UNSUPPORTED(24자)다';
COMMENT ON DOMAIN D_NM   IS '명 — 이름. 첨부파일명이 공고 제목을 그대로 쓰는 경우가 있어 300으로 잡았다';
COMMENT ON DOMAIN D_TTL  IS '제목 — 고시문 제목은 한 문장을 통째로 쓰는 일이 잦아 명보다 길게 잡는다';
COMMENT ON DOMAIN D_DT   IS '일자 — 날짜만. 시각이 필요하면 일시(D_DTM)를 쓴다';
COMMENT ON DOMAIN D_DTM  IS '일시 — 타임존 포함. 감사 컬럼이 이 도메인이다';
COMMENT ON DOMAIN D_YN   IS '여부 — BOOLEAN을 쓰지 않는다. 분류어 YN이 지시하는 도메인이 CHAR(1)인데 실제 타입이 BOOLEAN이면 "분류어만 보고 타입을 안다"는 규칙이 무너진다';
COMMENT ON DOMAIN D_CTNT IS '내용 — 길이를 예측할 수 없는 자유 텍스트';
COMMENT ON DOMAIN D_PATH IS '경로 — 파일 시스템 절대경로';
COMMENT ON DOMAIN D_HASH IS '해시 — SHA-256 16진 표기 64자. CHAR(64)만으로는 부족해 CHECK을 건다: character(n)은 짧은 값을 거부하지 않고 공백으로 채워 넣으므로, 잘린 해시가 조용히 들어온다. 소문자만 받는 것도 일부러다 — 같은 해시가 대소문자 두 벌로 저장되면 SRC_KEY_HASH의 UNIQUE가 중복을 못 잡는다. 적재하는 쪽이 소문자로 맞춰 넣어야 한다';
COMMENT ON DOMAIN D_URL  IS 'URL — 웹 주소. 경로(D_PATH)는 파일 시스템 절대경로 전용이라 쓸 수 없다. URL은 질의문자열·앵커가 붙어 더 길다';

-- ---------------------------------------------------------------------------
-- 2. 테이블
-- ---------------------------------------------------------------------------
-- 선언 순서는 FK 의존을 따른다. 제약조건 이름은 PostgreSQL에 맡기지 않고 여기서 준다 —
-- 자동 발급 이름(agncy_bas_pkey)은 테이블을 개명해도 따라오지 않아 옛 이름이 남는다.
-- 제약조건 이름은 질의에 등장하지 않으므로 30바이트 관행을 적용하지 않는다.

CREATE TABLE OS_INSTT_BAS (
    INSTT_SN      D_SN  NOT NULL,
    INSTT_NM      D_NM  NOT NULL,
    INSTT_KND_CD  D_CD  NOT NULL,
    FRST_REG_DTM  D_DTM NOT NULL DEFAULT now(),
    LAST_CHG_DTM  D_DTM NOT NULL DEFAULT now(),
    CONSTRAINT OS_INSTT_BAS_PK PRIMARY KEY (INSTT_SN)
);
COMMENT ON TABLE OS_INSTT_BAS IS '기관 — 고시·공고를 수집한 기관 게시판. 입력 폴더 하나가 한 행이다';
COMMENT ON COLUMN OS_INSTT_BAS.INSTT_SN IS
    '기관일련번호. 입력 폴더명 앞 번호이며, 한 번호 아래 이름이 갈리면(전자관보) 그 번호는 비우고 최대번호 뒤로 새로 발급한다 — AgencyRegistry가 폴더 집합 전체를 보고 정하므로 같은 입력이면 매번 같은 값이다';
COMMENT ON COLUMN OS_INSTT_BAS.INSTT_NM IS
    '기관명 — 입력 폴더명에서 온다. 문서 본문의 발령 기관명은 매핑 단계가 읽기는 하지만 적재하지 않는다(기관 추적은 이 컬럼으로 충분하다)';
COMMENT ON COLUMN OS_INSTT_BAS.INSTT_KND_CD IS
    '기관종류코드(CD_INSTT_KND): MOF 지방해양수산청 / LOCL 지방자치단체 / CNTL 중앙행정기관 / GZT 전자관보. GZT는 기관 종류가 아니라 수집 경로다 — 같은 부처를 자체 게시판(CNTL)과 전자관보 양쪽에서 긁으므로, 이름이 같은 두 행을 이 값으로 가린다';

CREATE TABLE OS_NOTI_BAS (
    NOTI_SN       D_SN   NOT NULL,
    INSTT_SN      D_SN,
    BBS_STTS_CD   D_CD,
    SRC_KEY_CTNT  D_CTNT,
    SRC_KEY_HASH  D_HASH,
    BBS_URL       D_URL,
    BBS_TTL       D_TTL,
    CRWL_KND_CD   D_CD,
    CHRG_DEPT_NM  D_NM,
    CHRG_PSN_NM   D_NM,
    TEL_NO        D_NO,
    NOTI_NO       D_NO,
    NOTI_DT       D_DT,
    FRST_REG_DTM  D_DTM  NOT NULL DEFAULT now(),
    LAST_CHG_DTM  D_DTM  NOT NULL DEFAULT now(),
    CONSTRAINT OS_NOTI_BAS_PK PRIMARY KEY (NOTI_SN),
    CONSTRAINT OS_NOTI_BAS_UK01 UNIQUE (SRC_KEY_HASH),
    CONSTRAINT OS_NOTI_BAS_FK01 FOREIGN KEY (INSTT_SN)
        REFERENCES OS_INSTT_BAS(INSTT_SN)
);
COMMENT ON TABLE OS_NOTI_BAS IS
    '고시공고게시물 — 게시물 1건. 크롤러 게시물 목록 엑셀 한 줄이 한 행이며, 같은 게시물의 첨부를 묶는 키를 겸한다. 첨부가 없거나 추출에 실패한 게시물도 제목·URL·담당자는 여기 남는다';
COMMENT ON COLUMN OS_NOTI_BAS.NOTI_SN IS
    '고시공고일련번호 = 크롤러 게시물 목록 엑셀의 번호. 첨부파일명 "{순번}_{제목}" 또는 "{순번}_{첨부순번}_{제목}"의 앞자리와 같은 값이다. 실행마다 1로 되돌아가지 않고 마지막 번호 뒤로 이어지므로 기관 스코프가 필요 없다. 문서 본문의 고시번호(OS_ATCH_FILE_DTL.NOTI_NO)와는 다른 것이다. 크롤 산출물이 아닌 파일은 음수를 발급받아 크롤 순번과 겹치지 않는다';
COMMENT ON COLUMN OS_NOTI_BAS.INSTT_SN IS
    '기관일련번호 — 크롤러가 발급한 기관번호에서 채운다. 크롤 산출물이 아닌 파일은 입력 폴더명에서 채우고, 폴더 규약 밖에서 온 파일(수동 수집분, 입력 루트 직속)은 NULL이다';
COMMENT ON COLUMN OS_NOTI_BAS.BBS_STTS_CD IS
    '게시상태코드(CD_BBS_STTS): POST 게시중 / CLSD 게시완료. 폴더명 꼬리표로 가른다 — "지난·이전·완료"류면 CLSD, 꼬리표가 없거나 "게시중·고시공고"류면 POST. 같은 기관이 게시판을 둘 운영하면(12_1 / 12_2) 기관은 하나고 이 값만 갈린다';
COMMENT ON COLUMN OS_NOTI_BAS.SRC_KEY_CTNT IS
    '원문키내용 — 지자체 홈페이지가 게시물을 식별하는, 사람이 읽는 키 원문이다([기관번호:게시물 식별 방식:게시물 번호]). 해시만 두지 않는 이유는 SHA-256을 되돌릴 수 없기 때문이다 — 중복 오판이 났을 때 "이 행이 어느 게시물인가"를 댈 근거가 이 컬럼뿐이다';
COMMENT ON COLUMN OS_NOTI_BAS.SRC_KEY_HASH IS
    '원문키해시 — SRC_KEY_CTNT의 SHA-256 소문자 16진 64자(도메인 CHECK이 형태를 강제한다). 같은 게시물을 두 번 담지 않게 막는 열쇠라 UNIQUE다. NOT NULL이 아닌 것은 크롤 산출물이 아닌 행(음수 NOTI_SN)에는 원문키가 없기 때문이고, PostgreSQL의 UNIQUE는 NULL을 서로 다른 값으로 보므로 그런 행이 여럿 공존한다';
COMMENT ON COLUMN OS_NOTI_BAS.BBS_URL IS
    '게시물URL — 고시공고 상세 페이지 주소. 첨부파일만 긁던 시절에는 알 길이 없던 값이고, 추출 결과를 원본과 대조할 때 유일한 통로다';
COMMENT ON COLUMN OS_NOTI_BAS.BBS_TTL IS
    '게시물제목 — 게시판 목록에 걸린 제목. 문서 본문의 고시제목(OS_ATCH_FILE_DTL.NOTI_TTL)과는 다른 것이다. 첨부가 없거나 추출에 실패한 게시물도 이 값은 남으므로, 무엇을 못 읽었는지 셀 수 있다';
COMMENT ON COLUMN OS_NOTI_BAS.CRWL_KND_CD IS
    '크롤종류코드(CD_CRWL_KND): SAMPLE 표본 / FULL_CRAWL 전수 / DAILY_NEW 일일 신규. 이 행을 만들어 낸 크롤러의 갈래이며, 전수 수집분과 증분분을 갈라 세는 축이다. 크롤러의 CRAWL_MODE가 정의처라 값을 그대로 쓴다';
COMMENT ON COLUMN OS_NOTI_BAS.CHRG_DEPT_NM IS '담당부서명 — 게시판이 표기한 담당부서';
COMMENT ON COLUMN OS_NOTI_BAS.CHRG_PSN_NM IS '담당자명 — 게시판이 표기한 담당자 이름';
COMMENT ON COLUMN OS_NOTI_BAS.TEL_NO IS
    '전화번호 — 담당자 연락처. 크롤러는 100자로 받지만 번호(D_NO)는 50자다. 내선·복수번호가 섞여 넘치는 값이 나오면 도메인 폭을 올린다';
COMMENT ON COLUMN OS_NOTI_BAS.NOTI_NO IS
    '고시번호 — 게시판이 목록에 표기한 값. 같은 이름의 OS_ATCH_FILE_DTL.NOTI_NO는 문서 본문에서 추출한 값이며, 두 값이 어긋난 행이 곧 추출 검증 대상이다';
COMMENT ON COLUMN OS_NOTI_BAS.NOTI_DT IS
    '고시일자 — 게시판이 목록에 표기한 값. 고시번호와 같이 OS_ATCH_FILE_DTL 쪽은 문서 본문 추출값이다';

-- FK 컬럼에 인덱스를 붙인다. 기관별 수집 현황 집계가 이 스키마의 주 용도이고,
-- 기관 행을 지울 때 참조 검사가 OS_NOTI_BAS 전건을 훑는 것도 막는다.
-- BBS_STTS_CD·CRWL_KND_CD에는 만들지 않는다 — 값이 각각 2종·3종뿐이라 플래너가 순차 스캔을 고른다.
-- SRC_KEY_HASH에도 만들지 않는다 — UNIQUE 제약이 이미 인덱스를 하나 만든다.
CREATE INDEX OS_NOTI_BAS_IX01 ON OS_NOTI_BAS(INSTT_SN);
COMMENT ON INDEX OS_NOTI_BAS_IX01 IS '기관별 수집 현황 집계용 FK 인덱스. 기관 행을 지울 때 참조 검사가 게시물 전건을 훑는 것도 막는다';
CREATE INDEX OS_NOTI_BAS_IX02 ON OS_NOTI_BAS(NOTI_DT);
COMMENT ON INDEX OS_NOTI_BAS_IX02 IS '게시판이 표기한 고시일자 기준 기간 질의용';

-- 하루치 수집에서 기관 하나를 긁은 결과. 스케줄러가 하루에 한 번 돈다.
--
-- 성공도 행으로 남긴다. 실패만 적재하면 "돌았는데 새 고시가 없었다"와 "아예 안 돌았다"가
-- 둘 다 "행 없음"이 돼 조용한 수집 누락을 못 잡는다 — 일일 증분 수집에서 뒤엣것은 사고다.
-- 기관 90개 × 일 1회면 연 3만 행이라 비용은 없다.
--
-- 담는 것은 크롤러만 아는 것뿐이다 — 자기가 몇 건이라고 집계했는가, 어디서 넘어졌는가,
-- 언제 돌았는가. OS_NOTI_BAS를 보면 알 수 있는 값은 담지 않는다. 수집 기간(가장 이른/늦은
-- 고시일자)을 뺀 것이 그래서다: min/max(OS_NOTI_BAS.NOTI_DT)로 그대로 나오고, 08.07 산출물로
-- 대조하면 90개 게시판이 전부 일치한다. 게시상태코드도 같은 이유로 빼는데, 더 큰 이유는
-- 운영 단계에서 새로 긁히는 게시물이 전부 게시중(POST)이라는 것이다 — 게시완료 게시판까지
-- 뒤진 것은 과거 데이터를 전수로 긁어야 했던 첫 수집 때뿐이고, 그건 이미 끝났다.
--
-- OS_INSTT_BAS로 FK를 건다. 이 레포는 "결과가 0건이어도 기관은 등록한다"를 이미 규칙으로 갖고
-- 있으므로(AgencyRegistry), 크롤러가 시도한 기관은 수집 결과와 무관하게 기관 행이 생긴다.
-- 그래서 기관명을 여기 중복해 담지 않는다 — 기관명은 기관의 속성이지 로그의 속성이 아니다.
-- 기관을 특정조차 못 한 실패는 INSTT_SN이 NULL이고, 그때는 FAIL_MSG_CTNT가 설명을 떠맡는다.
--
-- 이 스키마에서 감사 컬럼을 두지 않는 유일한 테이블이다(명명규칙 ④의 예외). 쌓기만 하고
-- 고치지 않는 로그라 LAST_CHG_DTM이 FRST_REG_DTM과 영원히 같은 값이고, "언제 돌았나"는
-- CRWL_DT가 이미 답한다. 규칙 때문에 남겨 두면 읽는 쪽이 이 행을 갱신되는 것으로 잘못 읽는다.
-- 그래서 CRWL_DT가 이 테이블의 유일한 시간 축이다.
--
-- 크롤단계코드(CRWL_STEP_CD)도 두지 않는다. 허용값의 정의처인 크롤러 쪽이 통합 스키마에는
-- 필요 없는 값이라고 확인해 줬다 — 정의처가 안 쓰는 코드 컬럼은 영원히 NULL로 남는다.
-- 실패가 어디서 났는지는 FAIL_MSG_CTNT 원문이 설명한다.
CREATE TABLE OS_CRWL_LOG_DTL (
    CRWL_LOG_SN    D_SN   NOT NULL,
    INSTT_SN       D_SN,
    CRWL_DT        D_DT   NOT NULL,
    CRWL_KND_CD    D_CD,
    CRWL_STTS_CD   D_CD   NOT NULL,
    INSTT_BBS_URL  D_URL,
    NOTI_CNT       D_CNT,
    ATCH_FILE_CNT  D_CNT,
    FAIL_MSG_CTNT  D_CTNT,
    CONSTRAINT OS_CRWL_LOG_DTL_PK PRIMARY KEY (CRWL_LOG_SN),
    CONSTRAINT OS_CRWL_LOG_DTL_FK01 FOREIGN KEY (INSTT_SN)
        REFERENCES OS_INSTT_BAS(INSTT_SN)
);
COMMENT ON TABLE OS_CRWL_LOG_DTL IS
    '크롤로그 — 하루치 수집에서 기관 하나를 긁은 결과. 성공도 행으로 남겨 "오늘 이 기관을 돌았나"에 답한다. 크롤러만 아는 것(자기 집계·상태·언제 돌았나)만 담고, OS_NOTI_BAS에서 유도되는 값은 담지 않는다. 쌓기만 하고 고치지 않는 로그라 감사 컬럼을 면제받는다 — 시간 축은 CRWL_DT 하나다';
COMMENT ON COLUMN OS_CRWL_LOG_DTL.CRWL_LOG_SN IS
    '크롤로그일련번호 — 크롤러가 발급한 값을 그대로 받는다. 크롤러 쪽은 BIGSERIAL이지만 하루에 기관 수(현재 90) 규모라 일련번호(INTEGER)로 넘칠 일이 없다. 여기서 BIGINT 도메인을 새로 만들면 분류어 SN이 도메인 둘을 가리켜 표준이 무너진다';
COMMENT ON COLUMN OS_CRWL_LOG_DTL.INSTT_SN IS
    '기관일련번호 — OS_INSTT_BAS로 가는 FK다. 결과가 0건인 기관도 기관 행은 만들어지므로 FK가 막을 일이 없다. 기관을 특정조차 못 한 실패만 NULL이다';
COMMENT ON COLUMN OS_CRWL_LOG_DTL.CRWL_DT IS
    '크롤일자 — 이 수집이 돈 날. 스케줄러가 하루에 한 번 도므로 로그 한 줄을 가리키는 업무 날짜가 이 하나로 충분하다. 이 테이블에는 감사 컬럼이 없어 여기가 유일한 시간 축이다 — 지난 산출물을 나중에 다시 적재하더라도 적재한 날이 아니라 산출물이 만들어진 날을 명시해서 넣어야 한다. 산출물이 이 값을 싣고 오지 않으면 적재하는 쪽이 실행일로 채운다';
COMMENT ON COLUMN OS_CRWL_LOG_DTL.CRWL_KND_CD IS
    '크롤종류코드(CD_CRWL_KND): SAMPLE / FULL_CRAWL / DAILY_NEW. 첫 전수 수집분과 일일 증분분을 갈라 세는 축이다. 현재 산출물(엑셀)에는 이 값이 없어 비어 있다';
COMMENT ON COLUMN OS_CRWL_LOG_DTL.CRWL_STTS_CD IS
    '크롤상태코드(CD_CRWL_STTS): OK 수집 완료 / FAIL 실패. 성공도 행으로 남긴다 — 실패만 적재하면 "돌았는데 새 고시가 없었다"와 "아예 안 돌았다"가 둘 다 "행 없음"이 된다';
COMMENT ON COLUMN OS_CRWL_LOG_DTL.INSTT_BBS_URL IS
    '기관게시판URL — 긁은 게시판의 목록 페이지 주소(엑셀 "사이트 URL"). 게시물 하나를 가리키는 OS_NOTI_BAS.BBS_URL과는 다른 것이다';
COMMENT ON COLUMN OS_CRWL_LOG_DTL.NOTI_CNT IS
    '고시공고건수 — 크롤러가 집계한 수집 게시물 수. 실제 적재된 OS_NOTI_BAS 행수와 대조하는 것이 이 컬럼의 용도다. 08.07 산출물에서는 기관 73개 중 둘이 어긋났다(영광군청 465/466, 양양군청 120/121)';
COMMENT ON COLUMN OS_CRWL_LOG_DTL.ATCH_FILE_CNT IS
    '첨부파일건수 — 크롤러가 집계한 내려받은 첨부 수. 게시물보다 많을 수도(한 게시물에 첨부 여럿, 최대 10건) 적을 수도(첨부 없는 게시물 1,256건) 있다';
COMMENT ON COLUMN OS_CRWL_LOG_DTL.FAIL_MSG_CTNT IS
    '실패메시지내용 — 실패 원인 원문. 기관을 특정하지 못한 실패는 이 컬럼이 유일한 단서다';

-- 기관별 이력 조회와 "실패 행만" 뽑는 질의가 이 테이블의 주 용도다.
-- CRWL_DT에도 붙인다 — "오늘 안 돈 기관"이 매일 도는 감시 질의다.
CREATE INDEX OS_CRWL_LOG_DTL_IX01 ON OS_CRWL_LOG_DTL(INSTT_SN);
COMMENT ON INDEX OS_CRWL_LOG_DTL_IX01 IS '기관별 크롤 이력 조회용 FK 인덱스';
CREATE INDEX OS_CRWL_LOG_DTL_IX02 ON OS_CRWL_LOG_DTL(CRWL_STTS_CD);
COMMENT ON INDEX OS_CRWL_LOG_DTL_IX02 IS '실패 행만 뽑는 질의용';
CREATE INDEX OS_CRWL_LOG_DTL_IX03 ON OS_CRWL_LOG_DTL(CRWL_DT);
COMMENT ON INDEX OS_CRWL_LOG_DTL_IX03 IS '"오늘 안 돈 기관" 감시 질의용 — 매일 돈다';

CREATE TABLE OS_NOTI_KND_TC (
    NOTI_KND_CD       D_CD  NOT NULL,
    NOTI_KND_NM       D_NM  NOT NULL,
    HRNK_NOTI_KND_NM  D_NM  NOT NULL,
    FRST_REG_DTM      D_DTM NOT NULL DEFAULT now(),
    LAST_CHG_DTM      D_DTM NOT NULL DEFAULT now(),
    CONSTRAINT OS_NOTI_KND_TC_PK PRIMARY KEY (NOTI_KND_CD)
);
COMMENT ON TABLE OS_NOTI_KND_TC IS
    '공고종류 56종. 한 기관이 평균 12종을 발행하므로 기관으로는 종류를 구분할 수 없다';
COMMENT ON COLUMN OS_NOTI_KND_TC.NOTI_KND_CD IS
    '공고종류코드 — {계열}_{수식}_{행위} 조합이고 낱말은 모두 3자다(OCU_PRM = 점용·사용 허가, OCU_CHG_PRM = 변경허가). 상위분류는 HRNK_NOTI_KND_NM에도 있지만 코드만 보고도 계열을 알 수 있어야 필터가 쉽다. 규칙은 notice_types.json의 conventions.code에 있다';
COMMENT ON COLUMN OS_NOTI_KND_TC.NOTI_KND_NM IS
    '공고종류명 — 공고종류의 한글 이름. 정의처는 notice_types.json이고 ReferenceSync가 기동 시 맞춘다';
COMMENT ON COLUMN OS_NOTI_KND_TC.HRNK_NOTI_KND_NM IS
    '상위공고종류명 — 점용·사용 / 실시계획 / 매립 / 점용료·사용료 / 혼합 / 기타. UPPER_로 시작하면 SQL 함수명과 겹쳐 상위(HRNK)를 쓴다';

CREATE TABLE OS_NOTI_ITEM_TC (
    NOTI_ITEM_CD    D_CD  NOT NULL,
    NOTI_ITEM_NM    D_NM  NOT NULL,
    ITEM_SRS_NM     D_NM,
    ITEM_VAL_TY_CD  D_CD  NOT NULL,
    CORE_ITEM_YN    D_YN  NOT NULL DEFAULT 'N',
    FRST_REG_DTM    D_DTM NOT NULL DEFAULT now(),
    LAST_CHG_DTM    D_DTM NOT NULL DEFAULT now(),
    CONSTRAINT OS_NOTI_ITEM_TC_PK PRIMARY KEY (NOTI_ITEM_CD)
);
COMMENT ON TABLE OS_NOTI_ITEM_TC IS
    '공고항목 — 표준항목 40종. notice_items.json이 단일 정의처이며 ReferenceSync가 기동 시 upsert한다';
COMMENT ON COLUMN OS_NOTI_ITEM_TC.NOTI_ITEM_CD IS
    '공고항목코드 = notice_items.json의 canonical. 컬럼명이 아니라 행으로 쌓이는 코드 데이터이므로 표준단어로 분해되지 않는다';
COMMENT ON COLUMN OS_NOTI_ITEM_TC.NOTI_ITEM_NM IS
    '공고항목명 — 표준항목의 한글 표시명. 라벨 원문이 아니라 대표 이름이며, 문서마다 다르게 적힌 라벨은 notice_items.json의 동의어로 이 항목에 모인다';
COMMENT ON COLUMN OS_NOTI_ITEM_TC.ITEM_SRS_NM IS
    '항목계열명 — 같은 뜻을 문맥별로 다르게 부르는 항목들의 묶음. 준공·완료 수리 문서에는 점용·사용 면적이 아니라 준공면적이 오므로, 누락 검증은 항목이 아니라 계열 단위로 봐야 오탐이 없다';
COMMENT ON COLUMN OS_NOTI_ITEM_TC.ITEM_VAL_TY_CD IS
    '항목값유형코드(CD_ITEM_VAL_TY): TEXT 원문 보존 / DATE ISO 정규화 / DTRG daterange / NUM 수치';
COMMENT ON COLUMN OS_NOTI_ITEM_TC.CORE_ITEM_YN IS
    '주요항목여부 — 전수 표본 출현율 60% 이상인 주요 6항목. 누락 검증 가중치용이며 저장 위치와는 무관하다';

CREATE TABLE OS_ATCH_FILE_DTL (
    NOTI_SN            D_SN  NOT NULL,
    ATCH_SN            D_SN  NOT NULL,
    ATCH_FILE_NM       D_NM  NOT NULL,
    ATCH_FILE_PATH     D_PATH,
    PROC_STTS_CD       D_CD  NOT NULL,
    FAIL_STEP_CD       D_CD,
    FAIL_KND_CD        D_CD,
    FAIL_MSG_CTNT      D_CTNT,
    EXCL_RSN_CTNT      D_CTNT,
    FILE_EXTN_NM       D_NM,
    ACTL_FILE_EXTN_NM  D_NM,
    SCAN_YN            D_YN  NOT NULL DEFAULT 'N',
    EXTC_ENGN_NM       D_NM,
    NOTI_KND_CD        D_CD,
    NOTI_NO            D_NO,
    NOTI_DT            D_DT,
    NOTI_TTL           D_TTL,
    FRST_REG_DTM       D_DTM NOT NULL DEFAULT now(),
    LAST_CHG_DTM       D_DTM NOT NULL DEFAULT now(),
    CONSTRAINT OS_ATCH_FILE_DTL_PK PRIMARY KEY (NOTI_SN, ATCH_SN),
    CONSTRAINT OS_ATCH_FILE_DTL_FK01 FOREIGN KEY (NOTI_SN)
        REFERENCES OS_NOTI_BAS(NOTI_SN) ON DELETE CASCADE,
    CONSTRAINT OS_ATCH_FILE_DTL_FK02 FOREIGN KEY (NOTI_KND_CD)
        REFERENCES OS_NOTI_KND_TC(NOTI_KND_CD)
);
COMMENT ON TABLE OS_ATCH_FILE_DTL IS
    '첨부파일 — 파일 1건. 원본 절대경로, 문서 단위 메타(고시번호·고시일자·제목)와 추출 상태';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.ATCH_FILE_NM IS
    '첨부파일명 — 입력 파일명 원문. 손대지 않고 그대로 담는다: 게시물·첨부 일련번호를 되짚는 근거이자, 적재된 행과 원본 파일을 잇는 유일한 끈이다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.ATCH_SN IS
    '첨부일련번호. 첨부가 1건인 게시물은 파일명에 순번이 없어 항상 1이다. 결번은 버그가 아니라 미수집 신호이므로 다시 매겨 메우지 않는다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.ATCH_FILE_PATH IS
    '첨부파일경로 — 파이프라인이 실제로 읽은 원본 첨부파일의 정규화된 절대경로';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.PROC_STTS_CD IS
    '처리상태코드(CD_PROC_STTS): OK 정상 / FAIL 실패 / SKIP 항목값 적재제외. SKIP은 파일을 통째로 빼는 것이 아니다 — 첨부 행도 이미지도 들어가고 항목값·라벨값만 빠진다. FAIL·SKIP도 행으로 남긴다 — 성공만 적재하면 "추출 0건인 기관"이 DB에서 보이지 않는다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.FAIL_STEP_CD IS
    '실패단계코드(CD_FAIL_STEP): DTCT 판별 / EXTC 추출·매핑 / SAVE 산출물저장 / LOAD DB적재. 표해석·매핑에는 값을 두지 않는다 — 그 둘은 자체 실패를 정의하지 않아(TableInterpreter·Mapper에 throw가 없다) 거기서 나온 예외는 파일 문제가 아니라 버그이고 EXTC로 모인다. SAVE는 중간 산출물 파일 저장이지 DB 적재가 아니다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.FAIL_KND_CD IS
    '실패종류코드(CD_FAIL_KND) — detect.FailureKind가 정의처다. 적재 실패(FAIL_STEP_CD=''LOAD'')는 비운다: 그 갈래들은 문서 판별·추출의 축이라 적재 실패에 들어맞는 값이 없고, 억지로 OTHER를 넣으면 판별 실패 집계가 오염된다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.FAIL_MSG_CTNT IS
    '실패메시지내용 — 실패 원인 원문. 같은 실패종류 여러 건 중 무엇이 진짜 손상이고 무엇이 우리 파서 한계인지는 이 원문으로만 갈린다. 코드로 접히지 않으므로 실패종류코드와 함께 남긴다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.EXCL_RSN_CTNT IS
    '적재제외사유내용 — 사유 문자열이 아니라 그 판정의 측정값이다: "본문 8,355자 (임계 5,000자 초과)". 본문글자수 컬럼을 따로 두지 않았으므로 DB에서 본문 길이를 알 수 있는 유일한 자리이고, 잠정값인 적재 임계치(map.max-body-chars)를 재보정할 때 근거가 되는 것이 이 수치다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.FILE_EXTN_NM IS
    '파일확장자명 — 확장자가 내용과 어긋난 파일이 흔하다. FILE_EXTN_NM <> ACTL_FILE_EXTN_NM인 행이 곧 그 목록이다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.ACTL_FILE_EXTN_NM IS
    '실제파일확장자명 — 내용(매직바이트)으로 판정한 형식. REAL_은 PostgreSQL 부동소수 타입명과 겹쳐 실제(ACTL)를 쓴다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.SCAN_YN IS
    '스캔여부 — 본문이 텍스트가 아니라 이미지인 스캔본인지. 판별에 실패한 행은 단정할 수 없으므로 기본값 N이 그대로 남는다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.EXTC_ENGN_NM IS
    '추출엔진명 — 본문을 실제로 읽어 낸 엔진. 확장자가 아니라 매직바이트로 라우팅하므로, 같은 .hwp가 파일마다 다른 엔진으로 갈 수 있다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.NOTI_KND_CD IS
    '공고종류코드 — 제목으로 판정한다. 규칙은 notice_types.json의 keywords에 있고 NoticeTypes.classify가 판정한다. 제목이 없거나 56종에 자리가 없는 문서는 NULL이다 — 억지로 가장 비슷한 종류에 밀어 넣으면 종류별 집계가 조용히 오염된다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.NOTI_NO IS
    '고시번호 — 문서 본문의 "고시 제2026-47호". 게시물 일련번호(OS_NOTI_BAS.NOTI_SN)와도, 처분받은 허가번호(NOTI_ITEM_CD=''APV_NO'')와도 다른 것이다. 전수에서 고시번호와 허가번호가 같은 행은 0/1,067이다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.NOTI_DT IS
    '고시일자. 일자(DT)와 일시(DTM)를 분류어로 가르므로 이 컬럼은 DATE이고 FRST_REG_DTM은 TIMESTAMPTZ다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.NOTI_TTL IS
    '고시제목 — 문서에서 읽은 제목. 공고종류(NOTI_KND_CD) 판정의 입력이므로, 이 값이 비면 종류도 비는 것이 정상이다';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.FRST_REG_DTM IS '최초등록일시 — 이 행을 처음 적재한 시각';
COMMENT ON COLUMN OS_ATCH_FILE_DTL.LAST_CHG_DTM IS '최종변경일시 — 이 행을 마지막으로 갱신한 시각';

-- 처분 건수 컬럼을 두지 않는다. 그 값은 전부 자식 테이블 질의로 나온다:
--   처분이 몇 건인가          → COUNT(DISTINCT OS_NOTI_ITEM_VAL_DTL.DSPS_SN)
--   값을 하나도 못 넣은 첨부  → PROC_STTS_CD='OK'인데 LEFT JOIN 결과가 0건
-- "목록표 행 중 값을 하나도 못 읽은 행을 감지"하는 용도로도 쓸 수 없다 — 그런 행은
-- TableInterpreter가 레코드로 만들기 전에 버리므로(facts가 비면 행을 담지 않는다) 애초에
-- 세어지지 않는다. 유도할 수 없는 값이 하나도 없으니 적재 로직과 따로 동기화할 이유가 없다.

CREATE INDEX OS_ATCH_FILE_DTL_IX01 ON OS_ATCH_FILE_DTL(PROC_STTS_CD);
COMMENT ON INDEX OS_ATCH_FILE_DTL_IX01 IS '처리상태별 집계용(OK·FAIL·SKIP)';
CREATE INDEX OS_ATCH_FILE_DTL_IX02 ON OS_ATCH_FILE_DTL(NOTI_KND_CD);
COMMENT ON INDEX OS_ATCH_FILE_DTL_IX02 IS '공고종류별 집계용';
CREATE INDEX OS_ATCH_FILE_DTL_IX03 ON OS_ATCH_FILE_DTL(NOTI_DT);
COMMENT ON INDEX OS_ATCH_FILE_DTL_IX03 IS '문서 본문에서 추출한 고시일자 기준 기간 질의용';
CREATE INDEX OS_ATCH_FILE_DTL_IX04 ON OS_ATCH_FILE_DTL(ACTL_FILE_EXTN_NM);
COMMENT ON INDEX OS_ATCH_FILE_DTL_IX04 IS '실제 확장자별 집계용 — 확장자가 내용과 어긋난 파일을 찾을 때 탄다';

CREATE TABLE OS_ATCH_IMG_DTL (
    NOTI_SN        D_SN   NOT NULL,
    ATCH_SN        D_SN   NOT NULL,
    IMG_SN         D_SN   NOT NULL,
    IMG_CPTN_CTNT  D_CTNT NOT NULL,
    IMG_FILE_PATH  D_PATH NOT NULL,
    FRST_REG_DTM   D_DTM  NOT NULL DEFAULT now(),
    LAST_CHG_DTM   D_DTM  NOT NULL DEFAULT now(),
    CONSTRAINT OS_ATCH_IMG_DTL_PK PRIMARY KEY (NOTI_SN, ATCH_SN, IMG_SN),
    CONSTRAINT OS_ATCH_IMG_DTL_FK01 FOREIGN KEY (NOTI_SN, ATCH_SN)
        REFERENCES OS_ATCH_FILE_DTL(NOTI_SN, ATCH_SN) ON DELETE CASCADE
);
COMMENT ON TABLE OS_ATCH_IMG_DTL IS
    '첨부이미지 — 이미지는 처분 레코드가 아니라 첨부파일의 속성이다. 한 파일이 레코드 N건을 낳을 때 어느 레코드에 붙일지 정할 근거가 없다';
COMMENT ON COLUMN OS_ATCH_IMG_DTL.IMG_SN IS
    '이미지일련번호 — 한 첨부 안에서의 이미지 순번. 문서에 나온 순서이지 중요도가 아니다';
COMMENT ON COLUMN OS_ATCH_IMG_DTL.IMG_CPTN_CTNT IS
    '이미지캡션내용 — 이름과 달리 지금 들어가는 값은 캡션이 아니라 저장 파일명이다(DbLoader가 RawImage.name을 넣는다). 추출기는 캡션을 따로 읽어 두지만(RawImage.caption) 적재 경로가 그것을 쓰지 않는다. 이 컬럼이 NOT NULL이라 캡션 없는 이미지를 무엇으로 채울지 정하지 않으면 바로잡을 수 없다 — 고칠 때 그 결정이 먼저다';
COMMENT ON COLUMN OS_ATCH_IMG_DTL.IMG_FILE_PATH IS '이미지파일경로 — 추출한 이미지를 저장한 절대경로';

CREATE TABLE OS_NOTI_ITEM_VAL_DTL (
    NOTI_SN        D_SN   NOT NULL,
    ATCH_SN        D_SN   NOT NULL,
    DSPS_SN        D_SN   NOT NULL,
    NOTI_ITEM_CD   D_CD   NOT NULL,
    RPT_SN         D_SN   NOT NULL DEFAULT 1,
    ITEM_VAL_CTNT  D_CTNT NOT NULL,
    FRST_REG_DTM   D_DTM  NOT NULL DEFAULT now(),
    LAST_CHG_DTM   D_DTM  NOT NULL DEFAULT now(),
    CONSTRAINT OS_NOTI_ITEM_VAL_DTL_PK PRIMARY KEY (NOTI_SN, ATCH_SN, DSPS_SN, NOTI_ITEM_CD, RPT_SN),
    CONSTRAINT OS_NOTI_ITEM_VAL_DTL_FK01 FOREIGN KEY (NOTI_SN, ATCH_SN)
        REFERENCES OS_ATCH_FILE_DTL(NOTI_SN, ATCH_SN) ON DELETE CASCADE,
    CONSTRAINT OS_NOTI_ITEM_VAL_DTL_FK02 FOREIGN KEY (NOTI_ITEM_CD)
        REFERENCES OS_NOTI_ITEM_TC(NOTI_ITEM_CD)
);
COMMENT ON TABLE OS_NOTI_ITEM_VAL_DTL IS
    '공고항목값 — 40개 표준항목을 전부 동등하게 행으로 담는다';
COMMENT ON COLUMN OS_NOTI_ITEM_VAL_DTL.DSPS_SN IS
    '처분일련번호 = 목록표의 N번째 행. 단일 서식 문서는 항상 1. 이 컬럼이 없으면 목록표에서 장소·면적·성명이 짝을 잃고, 그 손실은 조용하며 사후 복구가 불가능하다';
COMMENT ON COLUMN OS_NOTI_ITEM_VAL_DTL.RPT_SN IS
    '반복일련번호 — 같은 처분에 같은 항목이 반복될 때의 순번. 변경 고시문의 당초/변경 대비표가 대표적이다';
COMMENT ON COLUMN OS_NOTI_ITEM_VAL_DTL.ITEM_VAL_CTNT IS
    '항목값내용 — Java가 정규화한 값. 날짜는 ISO, 기간은 daterange 리터럴, 정규화 실패나 TEXT 항목은 원문 그대로다. OS_NOTI_ITEM_TC.ITEM_VAL_TY_CD와 대조하면 실패 여부를 가릴 수 있다';

CREATE INDEX OS_NOTI_ITEM_VAL_DTL_IX01 ON OS_NOTI_ITEM_VAL_DTL(NOTI_ITEM_CD);
COMMENT ON INDEX OS_NOTI_ITEM_VAL_DTL_IX01 IS '항목코드별 집계용';

-- 표준항목으로 매핑되지 못한 값. 위 테이블과 모양이 같고 키 자리만 코드에서 라벨 원문으로 바뀐다.
--
-- OS_NOTI_ITEM_TC로 가는 FK를 두지 않는 것이 이 테이블의 전부다. FK가 있으면 사전에 없는 라벨은
-- INSERT가 거부되고, 배치 삽입이라 그 첨부의 항목값·이미지가 통째로 롤백된다 — 문서 하나가
-- 라벨 한 줄 때문에 DB에서 사라진다. 그렇다고 라벨을 OS_NOTI_ITEM_TC에 자동 등재하면
-- "서해배타적경제수역(eez)" 같은 것이 표준항목이 되므로, 등재는 사람이 notice_items.json을 고칠
-- 때만 일어나고 그때까지 값은 여기에 머문다.
--
-- 값을 정규화하지 않는다. 표준항목이 아니라 값 유형(OS_NOTI_ITEM_TC.ITEM_VAL_TY_CD)을 모르므로
-- 날짜인지 면적인지 판정할 근거가 없다 — 원문 그대로 담고 판정은 사전 등재 뒤로 미룬다.
--
-- 반복일련번호를 두지 않는다. 라벨:값이 레코드당 Map이라(NoticeRecord.extras) 한 처분 안에서
-- 라벨이 이미 유일하다.
CREATE TABLE OS_NOTI_LBL_VAL_DTL (
    NOTI_SN        D_SN   NOT NULL,
    ATCH_SN        D_SN   NOT NULL,
    DSPS_SN        D_SN   NOT NULL,
    ITEM_LBL_NM    D_NM   NOT NULL,
    ITEM_VAL_CTNT  D_CTNT NOT NULL,
    FRST_REG_DTM   D_DTM  NOT NULL DEFAULT now(),
    LAST_CHG_DTM   D_DTM  NOT NULL DEFAULT now(),
    CONSTRAINT OS_NOTI_LBL_VAL_DTL_PK PRIMARY KEY (NOTI_SN, ATCH_SN, DSPS_SN, ITEM_LBL_NM),
    CONSTRAINT OS_NOTI_LBL_VAL_DTL_FK01 FOREIGN KEY (NOTI_SN, ATCH_SN)
        REFERENCES OS_ATCH_FILE_DTL(NOTI_SN, ATCH_SN) ON DELETE CASCADE
);
COMMENT ON TABLE OS_NOTI_LBL_VAL_DTL IS
    '고시공고라벨값 — 표준항목으로 매핑되지 못한 값. 라벨 원문을 키로 담는다 — OS_NOTI_ITEM_TC로 가는 FK가 없어야 표준항목 레지스트리를 지키면서 값을 잃지 않는다. 사전 보강 판단이 내려질 때까지 값을 들고 있는 자리다';
COMMENT ON COLUMN OS_NOTI_LBL_VAL_DTL.ITEM_LBL_NM IS
    '항목라벨명 — 고시문이 적어 온 이름 원문. 사전이 정하는 코드가 아니므로 표준코드에 등재하지 않는다. Labels.acceptableExtra가 문장·좌표 오탐을 미리 걸러 낸 것만 들어온다';
COMMENT ON COLUMN OS_NOTI_LBL_VAL_DTL.ITEM_VAL_CTNT IS
    '항목값내용 — 원문 그대로. 값 유형을 모르므로 정규화하지 않는다';

-- 라벨별 빈도가 이 테이블의 주 질의다 — 어느 라벨을 사전에 올릴지 고르는 근거가 된다.
CREATE INDEX OS_NOTI_LBL_VAL_DTL_IX01 ON OS_NOTI_LBL_VAL_DTL(ITEM_LBL_NM);
COMMENT ON INDEX OS_NOTI_LBL_VAL_DTL_IX01 IS '라벨별 빈도 집계용 — 어느 라벨을 사전에 올릴지 고르는 근거가 된다';

-- ---------------------------------------------------------------------------
-- 3. 날짜·기간 질의용 인덱스
-- ---------------------------------------------------------------------------
-- 값을 date로 캐스팅하지 않는 이유: text→date 캐스팅은 DateStyle에 좌우돼 STABLE이고,
-- IMMUTABLE이 아닌 표현식은 인덱스에 쓸 수 없다("functions in index expression must be
-- marked IMMUTABLE"). ISO 날짜는 사전순 정렬이 곧 시간순 정렬이므로 문자열 그대로
-- 인덱싱하면 된다:
--   WHERE NOTI_ITEM_CD = 'APV_DT'
--     AND ITEM_VAL_CTNT >= '2026-01-01' AND ITEM_VAL_CTNT < '2027-01-01'
--
-- 정규식으로 ISO 형태만 거르는 부분 인덱스로 만들지 않는다. 플래너가 부분 인덱스를 쓰려면
-- 쿼리 조건이 인덱스 조건을 함의함을 증명해야 하는데, 정규식 함의는 증명하지 못해 인덱스가
-- 영영 선택되지 않는다. 정규화에 실패한 값은 어차피 ISO 범위 밖으로 정렬돼 걸리지 않는다.
CREATE INDEX OS_NOTI_ITEM_VAL_DTL_IX02 ON OS_NOTI_ITEM_VAL_DTL (NOTI_ITEM_CD, ITEM_VAL_CTNT);
COMMENT ON INDEX OS_NOTI_ITEM_VAL_DTL_IX02 IS '항목코드 + 값 복합. ISO 날짜는 사전순이 곧 시간순이라 문자열 그대로 범위 질의가 된다';

-- 기간은 사전순 트릭이 통하지 않는다(겹침 판정이 필요하다). 파싱을 IMMUTABLE 함수로 감싼다 —
-- ISO 8601 고정폭 표기는 DateStyle과 무관하게 같은 값으로 읽히므로 그 선언이 실제로 참이다.
-- 형식이 어긋나면 예외 대신 NULL을 돌려준다: 예외를 던지면 정규화 실패 행 하나가 인덱스
-- 생성을 통째로 막고, 그것을 정규식 부분 인덱스로 피하면 위와 같은 이유로 인덱스가 안 쓰인다.
CREATE OR REPLACE FUNCTION FC_OS_ISO_DATERANGE(t text) RETURNS daterange
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS
$$ SELECT CASE WHEN t ~ '^\[\d{4}-\d{2}-\d{2},\d{4}-\d{2}-\d{2}\]$'
               THEN daterange(substring(t from 2 for 10)::date,
                              substring(t from 13 for 10)::date, '[]') END $$;
COMMENT ON FUNCTION FC_OS_ISO_DATERANGE(text) IS
    'OS_NOTI_ITEM_VAL_DTL.ITEM_VAL_CTNT의 "[시작,종료]" 리터럴을 daterange로. 형식이 어긋나면 NULL. 인덱스 표현식에 쓰려고 IMMUTABLE로 선언했고, ISO 고정폭 표기는 DateStyle과 무관해 그 선언이 참이다';

CREATE INDEX OS_NOTI_ITEM_VAL_DTL_IX03
    ON OS_NOTI_ITEM_VAL_DTL USING gist (FC_OS_ISO_DATERANGE(ITEM_VAL_CTNT))
    WHERE NOTI_ITEM_CD = 'WORK_PRD';
COMMENT ON INDEX OS_NOTI_ITEM_VAL_DTL_IX03 IS '공사기간 겹침 판정용 GiST. 기간은 사전순 트릭이 통하지 않는다';

-- ---------------------------------------------------------------------------
-- 4. 뷰
-- ---------------------------------------------------------------------------
-- EAV로 담은 항목값을 다시 컬럼으로 펴는 자리다. 40항목을 전부 펴지는 않는다 — 그러면 맨
-- 위에서 EAV를 고른 이유를 그대로 되돌리는 것이 된다. 여기서 펴는 것은 전역 출현율 60%를
-- 넘는 주요 6항목(OS_NOTI_ITEM_TC.CORE_ITEM_YN='Y')뿐이고, 그 여섯은 성기지 않다.
--
-- 한 행이 첨부파일 1건이다. 그래서 처분이 여럿인 목록표 문서는 여섯 항목이 처분별로 갈리지
-- 않고 한 칸에 합쳐진다 — 장소·면적·성명의 짝이 이 뷰에서는 보이지 않는다. 값이 사라지는
-- 것은 아니다(뷰는 원본을 가리지 않는다). 짝이 필요한 질의는 이 뷰가 아니라
-- OS_NOTI_ITEM_VAL_DTL을 DSPS_SN으로 묶어 봐야 한다.
--
-- 처리상태가 정상(OK)인 첨부는 전부 담는다. 처리상태코드는 OK·FAIL·SKIP 셋뿐이므로 이 조건이
-- 곧 "추출 실패와 항목값 적재제외만 뺀 나머지"다. 여섯 항목을 하나도 뽑지 못한 첨부도 여섯
-- 칸이 NULL인 행으로 남는다 — 위에서 "값을 하나도 못 넣은 첨부"라고 부른 그 부류이고, 그
-- 행이 곧 추출 품질 점검 대상이다.
--
-- 꼬리 _VW가 뷰를 지시한다(표준 사전 tableSuffixes). 뷰는 저장되지 않는 파생 객체이므로
-- 사전의 테이블 목록에도 표준용어에도 등재하지 않는다 — 사전이 정하는 것은 저장되는 컬럼의
-- 이름이고, 이 뷰의 컬럼은 이미 사전이 정한 이름들에서 나온다.
CREATE VIEW OS_ATCH_CORE_ITEM_VW AS
SELECT F.NOTI_SN,
       F.ATCH_SN,
       I.INSTT_NM,
       N.CHRG_DEPT_NM,
       N.CHRG_PSN_NM,
       K.NOTI_KND_NM,
       V.LOC_VAL_CTNT,
       V.AREA_VAL_CTNT,
       V.PRPS_VAL_CTNT,
       V.APLC_NM_VAL_CTNT,
       V.WORK_PRD_VAL_CTNT,
       V.APLC_ADDR_VAL_CTNT,
       COALESCE(G.ATCH_IMG_CNT, 0)::D_CNT AS ATCH_IMG_CNT
  FROM OS_ATCH_FILE_DTL F
  JOIN OS_NOTI_BAS      N ON N.NOTI_SN = F.NOTI_SN
  -- 기관·공고종류는 LEFT다. 둘 다 NULL을 허용하는 컬럼이라(폴더 규약 밖에서 온 파일,
  -- 56종에 자리가 없는 제목) INNER로 묶으면 멀쩡한 첨부가 조용히 빠진다.
  LEFT JOIN OS_INSTT_BAS   I ON I.INSTT_SN    = N.INSTT_SN
  LEFT JOIN OS_NOTI_KND_TC K ON K.NOTI_KND_CD = F.NOTI_KND_CD
  -- 항목값과 이미지를 각각 먼저 접은 뒤 붙인다. 둘을 그냥 조인하면 항목값 행수 × 이미지
  -- 행수로 불어나 이미지 개수가 뻥튀기된다.
  --
  -- 항목값도 LEFT다. 정상 처리된 첨부인데 여섯 항목이 하나도 안 나오는 경우가 있다 — 준공
  -- 문서의 면적은 AREA가 아니라 CMPL_AREA로 가고(계열이 갈린다), 취소 고시문은 취소일자
  -- 하나로 끝난다. INNER로 묶으면 그런 첨부가 뷰에서 통째로 사라져 "이 파일에서 주요항목을
  -- 못 뽑았다"를 여기서 셀 수 없게 된다.
  LEFT JOIN (
      SELECT NOTI_SN, ATCH_SN,
             string_agg(DISTINCT ITEM_VAL_CTNT, ' | ' ORDER BY ITEM_VAL_CTNT)
                 FILTER (WHERE NOTI_ITEM_CD = 'LOC')       AS LOC_VAL_CTNT,
             string_agg(DISTINCT ITEM_VAL_CTNT, ' | ' ORDER BY ITEM_VAL_CTNT)
                 FILTER (WHERE NOTI_ITEM_CD = 'AREA')      AS AREA_VAL_CTNT,
             string_agg(DISTINCT ITEM_VAL_CTNT, ' | ' ORDER BY ITEM_VAL_CTNT)
                 FILTER (WHERE NOTI_ITEM_CD = 'PRPS')      AS PRPS_VAL_CTNT,
             string_agg(DISTINCT ITEM_VAL_CTNT, ' | ' ORDER BY ITEM_VAL_CTNT)
                 FILTER (WHERE NOTI_ITEM_CD = 'APLC_NM')   AS APLC_NM_VAL_CTNT,
             string_agg(DISTINCT ITEM_VAL_CTNT, ' | ' ORDER BY ITEM_VAL_CTNT)
                 FILTER (WHERE NOTI_ITEM_CD = 'WORK_PRD')  AS WORK_PRD_VAL_CTNT,
             string_agg(DISTINCT ITEM_VAL_CTNT, ' | ' ORDER BY ITEM_VAL_CTNT)
                 FILTER (WHERE NOTI_ITEM_CD = 'APLC_ADDR') AS APLC_ADDR_VAL_CTNT
        FROM OS_NOTI_ITEM_VAL_DTL
       WHERE NOTI_ITEM_CD IN ('LOC', 'AREA', 'PRPS', 'APLC_NM', 'WORK_PRD', 'APLC_ADDR')
       GROUP BY NOTI_SN, ATCH_SN
  ) V ON V.NOTI_SN = F.NOTI_SN AND V.ATCH_SN = F.ATCH_SN
  LEFT JOIN (
      SELECT NOTI_SN, ATCH_SN, count(*) AS ATCH_IMG_CNT
        FROM OS_ATCH_IMG_DTL
       GROUP BY NOTI_SN, ATCH_SN
  ) G ON G.NOTI_SN = F.NOTI_SN AND G.ATCH_SN = F.ATCH_SN
 WHERE F.PROC_STTS_CD = 'OK';

COMMENT ON VIEW OS_ATCH_CORE_ITEM_VW IS
    '첨부주요항목 — 첨부파일 1건이 한 행. 기관·담당부서·담당자·공고종류에 주요 6항목과 이미지 개수를 붙여 편 요약이다. 처리상태가 정상(OK)인 첨부만 담는다. 이름을 그대로 빌려 온 컬럼(NOTI_SN·ATCH_SN·INSTT_NM·CHRG_DEPT_NM·CHRG_PSN_NM·NOTI_KND_NM)은 뜻도 원본 그대로이므로 설명을 여기 되풀이하지 않는다 — 원본 테이블의 컬럼 주석을 보면 된다. 뷰에서 새로 생긴 컬럼만 아래에 설명한다';
COMMENT ON COLUMN OS_ATCH_CORE_ITEM_VW.LOC_VAL_CTNT IS
    '점용·사용 장소 — 공고항목 LOC. 한 첨부에 값이 여럿이면 중복을 지우고 '' | ''로 이었다. 그 구분자는 읽으라고 붙인 것이지 파싱하라고 둔 것이 아니다 — 처분별로 갈라 보려면 OS_NOTI_ITEM_VAL_DTL을 DSPS_SN으로 묶어야 한다';
COMMENT ON COLUMN OS_ATCH_CORE_ITEM_VW.AREA_VAL_CTNT IS
    '점용·사용 면적 — 공고항목 AREA. 준공·완료 수리 문서는 면적이 준공면적(CMPL_AREA)으로 가므로 이 칸이 빈다. 그것은 누락이 아니라 계열이 갈린 것이다';
COMMENT ON COLUMN OS_ATCH_CORE_ITEM_VW.PRPS_VAL_CTNT IS
    '점용·사용 목적 — 공고항목 PRPS. 목적과 사업내용을 한 칸에 적어 온 문서는 PRPS가 아니라 PRPS_CTNT로 가므로 여기 오지 않는다';
COMMENT ON COLUMN OS_ATCH_CORE_ITEM_VW.APLC_NM_VAL_CTNT IS
    '허가·승인 대상자 성명 — 공고항목 APLC_NM. 공고·처분 대상자 성명(SBJT_NM)이나 사업 시행자 성명(OPER_NM)과는 다른 것이다';
COMMENT ON COLUMN OS_ATCH_CORE_ITEM_VW.WORK_PRD_VAL_CTNT IS
    '점용·사용 기간 — 공고항목 WORK_PRD. daterange 리터럴 "[시작,종료]" 원문이므로 기간 비교는 문자열이 아니라 FC_OS_ISO_DATERANGE()로 해야 한다. 정규화에 실패한 값은 그 함수가 NULL을 돌려준다';
COMMENT ON COLUMN OS_ATCH_CORE_ITEM_VW.APLC_ADDR_VAL_CTNT IS
    '허가·승인 대상자 주소 — 공고항목 APLC_ADDR. 공고·처분 대상자 주소(SBJT_ADDR)와는 다른 것이다';
COMMENT ON COLUMN OS_ATCH_CORE_ITEM_VW.ATCH_IMG_CNT IS
    '첨부이미지건수 — 첨부 문서 안에서 뽑아낸 이미지 수. 첨부 자체가 이미지 파일인 것과는 다른 얘기이며 그쪽은 ACTL_FILE_EXTN_NM이 답한다. 이미지가 없으면 NULL이 아니라 0이다 — 세는 컬럼이 NULL이면 합계·평균이 조용히 어긋난다';
