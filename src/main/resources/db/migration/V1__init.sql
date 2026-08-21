-- 공유수면 고시공고 스키마 — 7 엔터티. 이름은 행정표준용어를 따른다.
--
-- 계층: 기관 → 게시물 → 첨부파일 → (처분 레코드) 항목값
--   TB_AGNCY          기관 게시판. 입력 폴더명에서 만든다
--   TB_NOTI           게시물 = 크롤 순번. 같은 게시물의 첨부를 묶는 키
--   TB_ATCH_FILE      파일 1건. 문서 단위 메타(고시번호·고시일자·제목·고시자)와 추출 상태
--   TB_ATCH_IMG       첨부에 딸린 이미지
--   TB_NOTI_KND       공고종류 55종
--   TB_NOTI_ITEM      표준항목 40종. synonyms.json이 정의처이고 ReferenceSync가 동기화한다
--   TB_NOTI_ITEM_VAL  항목값. 40개 표준항목을 전부 동등하게 행으로 담는다
--
-- 식별자는 대문자로 적지만 PostgreSQL이 따옴표 없는 식별자를 소문자로 접으므로 실제 저장은
-- tb_agncy·agncy_no다. 큰따옴표로 대문자를 강제하지 않는다 — 한 번 강제하면 이후 모든 질의가
-- 영원히 따옴표를 달아야 하고, 하나만 빠뜨려도 "relation does not exist"로 죽는다.
--
-- 값 컬럼을 두지 않고 전부 EAV로 담는 이유:
--   ① 종류마다 등장하는 항목 집합이 다르고 극단적으로 성기다. 전역 출현율이 60%를 넘는
--      항목은 6개뿐이고 최하위는 0.01%다. 40컬럼으로 펴면 대부분 NULL이 된다.
--   ② 항목 레지스트리 자체가 분석 회차마다 바뀐다(08.06판 43항목 → 08.07판 40항목).
--      컬럼으로 박으면 그때마다 마이그레이션을 다시 짜야 한다.
--   ③ "종류별 항목 출현율" 같은 집계가 40컬럼 UNION이 아니라 GROUP BY 한 줄이 된다.

CREATE TABLE IF NOT EXISTS TB_AGNCY (
    AGNCY_NO   INT  PRIMARY KEY,   -- 기관번호
    AGNCY_NM   TEXT NOT NULL,      -- 기관명
    KND_CD     TEXT NOT NULL       -- mof(지방해양수산청) / local(지자체) / central(중앙) / gazette(전자관보 경유)
);
COMMENT ON TABLE TB_AGNCY IS '고시·공고를 수집한 기관 게시판. 입력 폴더 하나가 한 행이다';
COMMENT ON COLUMN TB_AGNCY.AGNCY_NO IS
    '입력 폴더명 앞 번호. 한 번호 아래 이름이 갈리면(전자관보) 그 번호는 비우고 최대번호 뒤로 새로 발급한다 — AgencyRegistry가 폴더 집합 전체를 보고 정하므로 같은 입력이면 매번 같은 값이다';
COMMENT ON COLUMN TB_AGNCY.AGNCY_NM IS
    '수집처 기관명 — 입력 폴더명에서 온다. 문서 본문의 발령 기관명은 매핑 단계가 읽기는 하지만 적재하지 않는다(기관 추적은 이 컬럼으로 충분하다)';
COMMENT ON COLUMN TB_AGNCY.KND_CD IS
    'gazette는 기관 종류가 아니라 수집 경로다. 같은 부처를 자체 게시판(central)과 전자관보(gazette) 양쪽에서 긁으므로, 이름이 같은 두 행을 이 값으로 가린다';

CREATE TABLE IF NOT EXISTS TB_NOTI (
    NOTI_SN    INT  PRIMARY KEY,   -- 고시공고 일련번호 = 첨부파일명 앞자리 크롤 순번
    AGNCY_NO   INT  REFERENCES TB_AGNCY(AGNCY_NO),
    BOARD_CD   TEXT               -- 게시중 / 게시완료
);
COMMENT ON TABLE TB_NOTI IS
    '게시물. 크롤링 대상이 첨부파일뿐이라 게시물 자체의 정보는 없고, 같은 게시물의 첨부를 묶는 키로만 쓴다';
COMMENT ON COLUMN TB_NOTI.NOTI_SN IS
    '크롤 일련번호. 파일명 "{순번}_{제목}" 또는 "{순번}_{첨부순번}_{제목}"의 앞자리다. 기관마다 연속 블록을 차지하고 기관 간 충돌이 없어 기관 스코프가 필요 없다. 문서 본문의 고시번호(TB_ATCH_FILE.NOTI_NO)와는 다른 것이다. 크롤 산출물이 아닌 파일은 음수를 발급받아 크롤 순번과 겹치지 않는다';
COMMENT ON COLUMN TB_NOTI.AGNCY_NO IS
    '입력 폴더명에서 채운다 — 첨부파일 안에는 수집처가 없어 폴더가 유일한 근거다. 폴더 규약 밖에서 온 파일(수동 수집분, 입력 루트 직속)은 NULL이다';
COMMENT ON COLUMN TB_NOTI.BOARD_CD IS
    '폴더명 꼬리표로 가른다 — "지난·이전·완료"류면 게시완료, 꼬리표가 없거나 "게시중·고시공고"류면 게시중. 같은 기관이 게시판을 둘 운영하면(12_1 / 12_2) 기관은 하나고 이 값만 갈린다';

-- FK 컬럼에 인덱스를 붙인다. 기관별 수집 현황 집계가 이 스키마의 주 용도이고,
-- 기관 행을 지울 때 참조 검사가 TB_NOTI 전건을 훑는 것도 막는다.
-- BOARD_CD에는 만들지 않는다 — 값이 2종뿐이라 플래너가 순차 스캔을 고른다.
CREATE INDEX IF NOT EXISTS IX_TB_NOTI_AGNCY_NO ON TB_NOTI(AGNCY_NO);

CREATE TABLE IF NOT EXISTS TB_NOTI_KND (
    NOTI_KND_CD   TEXT PRIMARY KEY,   -- 공고종류코드
    NOTI_KND_NM   TEXT NOT NULL,      -- 공고종류명
    UPPER_KND_NM  TEXT NOT NULL       -- 점용·사용 / 실시계획 / 매립 / 점용료·사용료 / 혼합 / 기타
);
COMMENT ON TABLE TB_NOTI_KND IS
    '공고종류 55종. 한 기관이 평균 12종을 발행하므로 기관으로는 종류를 구분할 수 없다';
COMMENT ON COLUMN TB_NOTI_KND.NOTI_KND_CD IS
    '{상위분류}_{행위} 조합(OCUPY_PRMSN = 점용·사용 허가). 상위분류는 UPPER_KND_NM에도 있지만 코드만 보고도 계열을 알 수 있어야 필터가 쉽다';

CREATE TABLE IF NOT EXISTS TB_NOTI_ITEM (
    ITEM_CD     TEXT PRIMARY KEY,   -- 표준항목코드 = synonyms.json의 canonical
    ITEM_NM     TEXT NOT NULL,      -- 표준항목명
    SRS_NM      TEXT,               -- 계열: 면적/기간/위치/인적/주소/날짜/연락/사유
    VAL_TY_CD   TEXT NOT NULL,      -- text / date / date_range / number
    CORE_YN     BOOLEAN NOT NULL DEFAULT FALSE
);
COMMENT ON TABLE TB_NOTI_ITEM IS
    '표준항목 40종. synonyms.json이 단일 정의처이며 ReferenceSync가 기동 시 upsert한다';
COMMENT ON COLUMN TB_NOTI_ITEM.SRS_NM IS
    '같은 뜻을 문맥별로 다르게 부르는 항목들의 묶음. 준공·완료 수리 문서에는 점용·사용 면적이 아니라 준공면적이 오므로, 누락 검증은 항목이 아니라 계열 단위로 봐야 오탐이 없다';
COMMENT ON COLUMN TB_NOTI_ITEM.CORE_YN IS
    '전수 표본 출현율 60% 이상인 주요 6항목 — 누락 검증 가중치용이며 저장 위치와는 무관하다';

CREATE TABLE IF NOT EXISTS TB_ATCH_FILE (
    NOTI_SN         INT  NOT NULL REFERENCES TB_NOTI(NOTI_SN) ON DELETE CASCADE,
    ATCH_SN         INT  NOT NULL,   -- 첨부 순번
    FILE_NM         TEXT NOT NULL,
    STTS_CD         TEXT NOT NULL,   -- ok / failed / skipped
    FAIL_STEP       TEXT,            -- 판별 / 추출 / 표해석 / 매핑
    FAIL_KND        TEXT,            -- detect.FailureKind
    FAIL_MSG        TEXT,
    EXCL_RSN        TEXT,            -- 적재제외 사유(안내문류 차단)
    FILE_EXTN       TEXT,            -- 파일명 확장자 기준 형식
    REAL_EXTN       TEXT,            -- 내용(매직바이트)으로 판정한 실제 형식
    SCAN_YN         BOOLEAN NOT NULL DEFAULT FALSE,
    ENGN_NM         TEXT,            -- 추출 엔진
    NOTI_KND_CD     TEXT REFERENCES TB_NOTI_KND(NOTI_KND_CD),
    NOTI_NO         TEXT,            -- 고시번호 (본문에서 추출, "고시 제2026-47호")
    NOTI_YMD        DATE,            -- 고시일자
    NOTI_TTL        TEXT,            -- 제목
    DSPS_CNT        INT NOT NULL DEFAULT 0,   -- 처분 레코드 수
    REG_DT          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (NOTI_SN, ATCH_SN)
);
COMMENT ON COLUMN TB_ATCH_FILE.ATCH_SN IS
    '첨부 순번. 첨부가 1건인 게시물은 파일명에 순번이 없어 항상 1이다. 결번은 버그가 아니라 미수집 신호이므로 다시 매겨 메우지 않는다';
COMMENT ON COLUMN TB_ATCH_FILE.FILE_EXTN IS
    '확장자가 내용과 어긋난 파일이 흔하다. FILE_EXTN <> REAL_EXTN인 행이 곧 그 목록이다';
COMMENT ON COLUMN TB_ATCH_FILE.STTS_CD IS
    'failed·skipped도 행으로 남긴다 — 성공만 적재하면 "추출 0건인 기관"이 DB에서 보이지 않는다';
COMMENT ON COLUMN TB_ATCH_FILE.NOTI_KND_CD IS
    '제목으로 판정한 공고종류. 규칙은 notice_types.json의 keywords에 있고 NoticeTypes.classify가 판정한다. 제목이 없거나(첨부 173건) 55종에 자리가 없는 문서(입찰공고 등)는 NULL이다 — 억지로 가장 비슷한 종류에 밀어 넣으면 종류별 집계가 조용히 오염된다';
COMMENT ON COLUMN TB_ATCH_FILE.NOTI_NO IS
    '문서 본문의 고시번호("고시 제2026-47호"). 게시물 일련번호(TB_NOTI.NOTI_SN)와도, 처분받은 허가번호(ITEM_CD=''APPROVAL_NO'')와도 다른 것이다. 전수에서 고시번호와 허가번호가 같은 행은 0/1,067이다';
COMMENT ON COLUMN TB_ATCH_FILE.DSPS_CNT IS
    '이 첨부에서 나온 처분 레코드 수. 목록표 행 중 값을 하나도 못 읽은 행을 감지하는 데 쓴다';

CREATE INDEX IF NOT EXISTS IX_TB_ATCH_FILE_STTS_CD   ON TB_ATCH_FILE(STTS_CD);
CREATE INDEX IF NOT EXISTS IX_TB_ATCH_FILE_NOTI_KND  ON TB_ATCH_FILE(NOTI_KND_CD);
CREATE INDEX IF NOT EXISTS IX_TB_ATCH_FILE_NOTI_YMD  ON TB_ATCH_FILE(NOTI_YMD);
CREATE INDEX IF NOT EXISTS IX_TB_ATCH_FILE_REAL_EXTN ON TB_ATCH_FILE(REAL_EXTN);

CREATE TABLE IF NOT EXISTS TB_ATCH_IMG (
    NOTI_SN     INT  NOT NULL,
    ATCH_SN     INT  NOT NULL,
    IMG_SN      INT  NOT NULL,
    IMG_CPTN    TEXT NOT NULL,   -- 이미지 캡션
    FILE_PATH   TEXT NOT NULL,   -- 저장 절대경로
    PRIMARY KEY (NOTI_SN, ATCH_SN, IMG_SN),
    FOREIGN KEY (NOTI_SN, ATCH_SN)
        REFERENCES TB_ATCH_FILE(NOTI_SN, ATCH_SN) ON DELETE CASCADE
);
COMMENT ON TABLE TB_ATCH_IMG IS
    '이미지는 처분 레코드가 아니라 첨부파일의 속성이다 — 한 파일이 레코드 N건을 낳을 때 어느 레코드에 붙일지 정할 근거가 없다';

CREATE TABLE IF NOT EXISTS TB_NOTI_ITEM_VAL (
    NOTI_SN    INT  NOT NULL,
    ATCH_SN    INT  NOT NULL,
    DSPS_SN    INT  NOT NULL,   -- 처분 순번 = 목록표의 N번째 행
    ITEM_CD    TEXT NOT NULL REFERENCES TB_NOTI_ITEM(ITEM_CD),
    RPT_SN     INT  NOT NULL DEFAULT 1,   -- 같은 항목 반복 순번
    ITEM_VAL   TEXT NOT NULL,
    PRIMARY KEY (NOTI_SN, ATCH_SN, DSPS_SN, ITEM_CD, RPT_SN),
    FOREIGN KEY (NOTI_SN, ATCH_SN)
        REFERENCES TB_ATCH_FILE(NOTI_SN, ATCH_SN) ON DELETE CASCADE
);
COMMENT ON COLUMN TB_NOTI_ITEM_VAL.DSPS_SN IS
    '목록표의 N번째 행 = 처분 1건. 단일 서식 문서는 항상 1. 이 컬럼이 없으면 목록표에서 장소·면적·성명이 짝을 잃고, 그 손실은 조용하며 사후 복구가 불가능하다';
COMMENT ON COLUMN TB_NOTI_ITEM_VAL.RPT_SN IS
    '같은 처분에 같은 항목이 반복될 때의 순번 — 변경 고시문의 당초/변경 대비표가 대표적이다';
COMMENT ON COLUMN TB_NOTI_ITEM_VAL.ITEM_VAL IS
    'Java가 정규화한 값. 날짜는 ISO, 기간은 daterange 리터럴, 정규화 실패나 text 항목은 원문 그대로다. TB_NOTI_ITEM.VAL_TY_CD와 대조하면 실패 여부를 가릴 수 있다';

CREATE INDEX IF NOT EXISTS IX_TB_NOTI_ITEM_VAL_ITEM_CD ON TB_NOTI_ITEM_VAL(ITEM_CD);

-- 날짜 범위 질의용 인덱스.
--
-- 값을 date로 캐스팅하지 않는 이유: text→date 캐스팅은 DateStyle에 좌우돼 STABLE이고,
-- IMMUTABLE이 아닌 표현식은 인덱스에 쓸 수 없다("functions in index expression must be
-- marked IMMUTABLE"). ISO 날짜는 사전순 정렬이 곧 시간순 정렬이므로 문자열 그대로
-- 인덱싱하면 된다:
--   WHERE ITEM_CD = 'APPROVAL_DATE' AND ITEM_VAL >= '2026-01-01' AND ITEM_VAL < '2027-01-01'
--
-- 정규식으로 ISO 형태만 거르는 부분 인덱스로 만들지 않는다. 플래너가 부분 인덱스를 쓰려면
-- 쿼리 조건이 인덱스 조건을 함의함을 증명해야 하는데, 정규식 함의는 증명하지 못해 인덱스가
-- 영영 선택되지 않는다. 정규화에 실패한 값은 어차피 ISO 범위 밖으로 정렬돼 걸리지 않는다.
CREATE INDEX IF NOT EXISTS IX_TB_NOTI_ITEM_VAL_VAL ON TB_NOTI_ITEM_VAL (ITEM_CD, ITEM_VAL);

-- 기간은 사전순 트릭이 통하지 않는다(겹침 판정이 필요하다). 파싱을 IMMUTABLE 함수로 감싼다 —
-- ISO 8601 고정폭 표기는 DateStyle과 무관하게 같은 값으로 읽히므로 그 선언이 실제로 참이다.
-- 형식이 어긋나면 예외 대신 NULL을 돌려준다: 예외를 던지면 정규화 실패 행 하나가 인덱스
-- 생성을 통째로 막고, 그것을 정규식 부분 인덱스로 피하면 위와 같은 이유로 인덱스가 안 쓰인다.
CREATE OR REPLACE FUNCTION iso_daterange(t text) RETURNS daterange
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS
$$ SELECT CASE WHEN t ~ '^\[\d{4}-\d{2}-\d{2},\d{4}-\d{2}-\d{2}\]$'
               THEN daterange(substring(t from 2 for 10)::date,
                              substring(t from 13 for 10)::date, '[]') END $$;
COMMENT ON FUNCTION iso_daterange(text) IS
    'TB_NOTI_ITEM_VAL.ITEM_VAL의 "[시작,종료]" 리터럴을 daterange로. 형식이 어긋나면 NULL. 인덱스 표현식에 쓰려고 IMMUTABLE로 선언했고, ISO 고정폭 표기는 DateStyle과 무관해 그 선언이 참이다';

CREATE INDEX IF NOT EXISTS IX_TB_NOTI_ITEM_VAL_WORK_PERIOD
    ON TB_NOTI_ITEM_VAL USING gist (iso_daterange(ITEM_VAL))
    WHERE ITEM_CD = 'WORK_PERIOD';
