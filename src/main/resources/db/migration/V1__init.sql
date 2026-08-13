-- 공유수면 고시공고 스키마 — 6 엔터티.
--
-- 계층: 기관 → 게시물 → 첨부파일 → (처분 레코드) 항목값
--   agencies             기관 71개
--   notices              게시물 = 크롤 순번. 같은 게시물의 첨부를 묶는 키
--   attachments          파일 1건. 문서 단위 메타(고시번호·고시일자·제목·고시자)와 추출 상태
--   attachment_images    첨부에 딸린 이미지
--   notice_types         공고종류 55종
--   attribute_defs       표준항목 40종. synonyms.json이 정의처이고 ReferenceSync가 동기화한다
--   document_attributes  항목값. 40개 표준항목을 전부 동등하게 행으로 담는다
--
-- 값 컬럼을 두지 않고 전부 EAV로 담는 이유:
--   ① 종류마다 등장하는 항목 집합이 다르고 극단적으로 성기다. 전역 출현율이 60%를 넘는
--      항목은 6개뿐이고 최하위는 0.01%다. 40컬럼으로 펴면 대부분 NULL이 된다.
--   ② 항목 레지스트리 자체가 분석 회차마다 바뀐다(08.06판 43항목 → 08.07판 40항목).
--      컬럼으로 박으면 그때마다 마이그레이션을 다시 짜야 한다.
--   ③ "종류별 항목 출현율" 같은 집계가 40컬럼 UNION이 아니라 GROUP BY 한 줄이 된다.

CREATE TABLE IF NOT EXISTS agencies (
    agency_no   INT  PRIMARY KEY,
    name        TEXT NOT NULL,
    kind_code   TEXT NOT NULL   -- mof(지방해양수산청) / local(지자체) / central(중앙·관보)
);
COMMENT ON TABLE agencies IS '고시·공고를 발령한 행정기관';

CREATE TABLE IF NOT EXISTS notices (
    notice_no   INT  PRIMARY KEY,   -- 첨부파일명 앞자리 크롤 순번. 기관을 넘어 전역 유일
    agency_no   INT  REFERENCES agencies(agency_no),
    board_code  TEXT                -- 게시중 / 게시완료
);
COMMENT ON TABLE notices IS
    '게시물. 크롤링 대상이 첨부파일뿐이라 게시물 자체의 정보는 없고, 같은 게시물의 첨부를 묶는 키로만 쓴다';
COMMENT ON COLUMN notices.notice_no IS
    '파일명 "{순번}_{첨부순번}_{제목}"의 앞자리. 전수 표본에서 83~21,751의 단일 크롤 시퀀스로 확인돼 기관 스코프가 필요 없다. 크롤 산출물이 아닌 파일은 음수를 발급받아 크롤 순번과 겹치지 않는다';
COMMENT ON COLUMN notices.agency_no IS
    '어느 기관 게시판을 긁었는지는 크롤러만 안다 — 추출 파이프라인은 채우지 못하므로 NULL로 남는다. 본문에서 읽은 기관명은 attachments.agency_name에 따로 있다';

CREATE TABLE IF NOT EXISTS notice_types (
    type_code   TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    family      TEXT NOT NULL   -- 점용·사용 / 실시계획 / 매립 / 점용료·사용료 / 혼합 / 기타
);
COMMENT ON TABLE notice_types IS
    '공고종류 55종. 한 기관이 평균 12종을 발행하므로 기관으로는 종류를 구분할 수 없다';

CREATE TABLE IF NOT EXISTS attribute_defs (
    attr_code   TEXT PRIMARY KEY,               -- = synonyms.json의 canonical
    name        TEXT NOT NULL,
    series      TEXT,                           -- 면적/기간/위치/인적/주소/날짜/연락/사유
    value_type  TEXT NOT NULL,                  -- text / date / date_range / number
    is_core     BOOLEAN NOT NULL DEFAULT FALSE
);
COMMENT ON TABLE attribute_defs IS
    '표준항목 40종. synonyms.json이 단일 정의처이며 ReferenceSync가 기동 시 upsert한다';
COMMENT ON COLUMN attribute_defs.series IS
    '같은 뜻을 문맥별로 다르게 부르는 항목들의 묶음. 준공·완료 수리 문서에는 점용·사용 면적이 아니라 준공면적이 오므로, 누락 검증은 항목이 아니라 계열 단위로 봐야 오탐이 없다';
COMMENT ON COLUMN attribute_defs.is_core IS
    '전수 표본 출현율 60% 이상인 주요 6항목 — 누락 검증 가중치용이며 저장 위치와는 무관하다';

CREATE TABLE IF NOT EXISTS attachments (
    notice_no     INT  NOT NULL REFERENCES notices(notice_no) ON DELETE CASCADE,
    attach_no     INT  NOT NULL,
    file_name     TEXT NOT NULL,
    status_code   TEXT NOT NULL,   -- ok / failed / skipped
    fail_stage    TEXT,            -- 판별 / 추출 / 표해석 / 매핑
    fail_kind     TEXT,            -- detect.FailureKind
    fail_message  TEXT,
    skip_reason   TEXT,            -- 안내문류 차단 사유
    ext_outer     TEXT,            -- 파일명 확장자 기준 형식
    ext_inner     TEXT,            -- 내용(매직바이트)으로 판정한 실제 형식
    is_scanned    BOOLEAN NOT NULL DEFAULT FALSE,
    engine        TEXT,
    type_code     TEXT REFERENCES notice_types(type_code),
    agency_name   TEXT,            -- 본문에서 읽은 기관명. notices.agency_no와 별개다
    doc_no        TEXT,            -- 고시번호 (본문에서 추출)
    doc_date      DATE,            -- 고시일자
    title         TEXT,
    signer        TEXT,
    record_count  INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (notice_no, attach_no)
);
COMMENT ON COLUMN attachments.ext_outer IS
    '확장자가 내용과 어긋난 파일이 흔하다. ext_outer <> ext_inner인 행이 곧 그 목록이다';
COMMENT ON COLUMN attachments.status_code IS
    'failed·skipped도 행으로 남긴다 — 성공만 적재하면 "추출 0건인 기관"이 DB에서 보이지 않는다';
COMMENT ON COLUMN attachments.record_count IS
    '이 첨부에서 나온 처분 레코드 수. 목록표 행 중 값을 하나도 못 읽은 행을 감지하는 데 쓴다';

CREATE INDEX IF NOT EXISTS idx_attachments_status    ON attachments(status_code);
CREATE INDEX IF NOT EXISTS idx_attachments_type      ON attachments(type_code);
CREATE INDEX IF NOT EXISTS idx_attachments_doc_date  ON attachments(doc_date);
CREATE INDEX IF NOT EXISTS idx_attachments_ext_inner ON attachments(ext_inner);

CREATE TABLE IF NOT EXISTS attachment_images (
    notice_no  INT  NOT NULL,
    attach_no  INT  NOT NULL,
    image_no   INT  NOT NULL,
    caption    TEXT NOT NULL,
    abs_path   TEXT NOT NULL,
    PRIMARY KEY (notice_no, attach_no, image_no),
    FOREIGN KEY (notice_no, attach_no)
        REFERENCES attachments(notice_no, attach_no) ON DELETE CASCADE
);
COMMENT ON TABLE attachment_images IS
    '이미지는 처분 레코드가 아니라 첨부파일의 속성이다 — 한 파일이 레코드 N건을 낳을 때 어느 레코드에 붙일지 정할 근거가 없다';

CREATE TABLE IF NOT EXISTS document_attributes (
    notice_no  INT  NOT NULL,
    attach_no  INT  NOT NULL,
    record_no  INT  NOT NULL,
    attr_code  TEXT NOT NULL REFERENCES attribute_defs(attr_code),
    seq        INT  NOT NULL DEFAULT 1,
    value      TEXT NOT NULL,
    PRIMARY KEY (notice_no, attach_no, record_no, attr_code, seq),
    FOREIGN KEY (notice_no, attach_no)
        REFERENCES attachments(notice_no, attach_no) ON DELETE CASCADE
);
COMMENT ON COLUMN document_attributes.record_no IS
    '목록표의 N번째 행 = 처분 1건. 단일 서식 문서는 항상 1. 이 컬럼이 없으면 목록표에서 장소·면적·성명이 짝을 잃고, 그 손실은 조용하며 사후 복구가 불가능하다';
COMMENT ON COLUMN document_attributes.seq IS
    '같은 레코드에 같은 항목이 반복될 때의 순번 — 변경 고시문의 당초/변경 대비표가 대표적이다';
COMMENT ON COLUMN document_attributes.value IS
    'Java가 정규화한 값. 날짜는 ISO, 기간은 daterange 리터럴, 정규화 실패나 text 항목은 원문 그대로다. attribute_defs.value_type과 대조하면 실패 여부를 가릴 수 있다';

CREATE INDEX IF NOT EXISTS idx_doc_attrs_code ON document_attributes(attr_code);

-- 날짜 범위 질의용 인덱스.
--
-- 값을 date로 캐스팅하지 않는 이유: text→date 캐스팅은 DateStyle에 좌우돼 STABLE이고,
-- IMMUTABLE이 아닌 표현식은 인덱스에 쓸 수 없다("functions in index expression must be
-- marked IMMUTABLE"). ISO 날짜는 사전순 정렬이 곧 시간순 정렬이므로 문자열 그대로
-- 인덱싱하면 된다:
--   WHERE attr_code = 'approval_date' AND value >= '2026-01-01' AND value < '2027-01-01'
--
-- 정규식으로 ISO 형태만 거르는 부분 인덱스로 만들지 않는다. 플래너가 부분 인덱스를 쓰려면
-- 쿼리 조건이 인덱스 조건을 함의함을 증명해야 하는데, 정규식 함의는 증명하지 못해 인덱스가
-- 영영 선택되지 않는다. 정규화에 실패한 값은 어차피 ISO 범위 밖으로 정렬돼 걸리지 않는다.
CREATE INDEX IF NOT EXISTS idx_doc_attrs_value ON document_attributes (attr_code, value);

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
    'document_attributes.value의 "[시작,종료]" 리터럴을 daterange로. 형식이 어긋나면 NULL. 인덱스 표현식에 쓰려고 IMMUTABLE로 선언했고, ISO 고정폭 표기는 DateStyle과 무관해 그 선언이 참이다';

CREATE INDEX IF NOT EXISTS idx_doc_attrs_work_period
    ON document_attributes USING gist (iso_daterange(value))
    WHERE attr_code = 'work_period';
