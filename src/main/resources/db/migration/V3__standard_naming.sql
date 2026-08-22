-- DB 표준화 — 표준단어·표준도메인·표준용어·표준코드 사전에 맞춰 이름과 타입을 다시 세운다.
--
-- 사전 본문은 src/main/resources/db/standard_terms.json이고 DbStandardTest가 이 파일과
-- 대조한다. 이름을 고칠 때는 사전을 먼저 고쳐야 한다 — 반대로 하면 테스트가 막는다.
--
-- V1을 고치지 않고 V3를 얹는 이유: Flyway는 적용된 마이그레이션의 체크섬을 검증하므로
-- V1을 손대면 이미 V1을 돌린 DB가 기동하지 못한다. 그래서 데이터를 보존한 채 ALTER로 옮긴다.
--
-- 무엇이 왜 바뀌는가:
--   ① 접두사 TB_ 제거, 유형 접미사 부여   TB_AGNCY → AGNCY_BAS, TB_NOTI_KND → NOTI_KND_TC
--      업무영역 접두사는 두지 않는다 — 단일 업무영역이라 모든 테이블에 같은 접두사가 붙어
--      아무것도 가르지 못한다. 코드 테이블은 _CD가 아니라 _TC다. _CD로 하면 테이블
--      NOTI_KND_CD와 그 안의 컬럼 NOTI_KND_CD가 같은 이름이 된다.
--   ② 분류어 부여                        FAIL_STEP → FAIL_STEP_CD, IMG_CPTN → IMG_CPTN_CTNT
--      마지막 낱말만 보고 타입을 알 수 있어야 한다.
--   ③ 전사 유일성 확보                   KND_CD → AGNCY_KND_CD, ITEM_CD → NOTI_ITEM_CD
--      컬럼명이 전사에서 유일한 뜻을 가져야 하므로 수식어를 붙인다. "테이블명을 컬럼에
--      반복하지 마라"는 웹 관행과는 체계가 다르다.
--   ④ 도메인 강제                        전 컬럼을 CREATE DOMAIN 타입으로 옮긴다
--   ⑤ 코드값 표준화                      'ok'/'게시중' → 'OK'/'POST'
--   ⑥ 감사 컬럼                          FRST_REG_DTM·LAST_CHG_DTM을 전 테이블에 같은 이름으로
--
-- 등록자·변경자 ID와 DEL_YN은 두지 않는다. 사람이 아니라 배치 CLI가 적재하므로 ID에 무엇을
-- 넣어도 가짜이고, 적재 멱등 단위가 첨부파일이라 삭제는 실제 DELETE로 일어난다.

-- ---------------------------------------------------------------------------
-- 0. 길이 사전 점검
-- ---------------------------------------------------------------------------
-- TEXT에서 길이 제한이 있는 도메인으로 옮기므로, 넘치는 값이 있으면 ALTER가 중간에 죽는다.
-- PostgreSQL이 내는 "value too long" 메시지에는 어느 행인지가 없어 원인을 찾을 수 없다.
-- 먼저 훑어 무엇이 걸리는지 이름째로 알려 주고 멈춘다.
DO $$
DECLARE
    offender text;
BEGIN
    SELECT string_agg(msg, E'\n  ') INTO offender FROM (
        SELECT 'TB_AGNCY.AGNCY_NM(' || length(AGNCY_NM) || '자): ' || AGNCY_NM AS msg
          FROM TB_AGNCY WHERE length(AGNCY_NM) > 300
        UNION ALL
        SELECT 'TB_NOTI_KND.NOTI_KND_NM(' || length(NOTI_KND_NM) || '자): ' || NOTI_KND_NM
          FROM TB_NOTI_KND WHERE length(NOTI_KND_NM) > 300
        UNION ALL
        SELECT 'TB_NOTI_ITEM.ITEM_NM(' || length(ITEM_NM) || '자): ' || ITEM_NM
          FROM TB_NOTI_ITEM WHERE length(ITEM_NM) > 300
        UNION ALL
        SELECT 'TB_ATCH_FILE.FILE_NM(' || length(FILE_NM) || '자): ' || FILE_NM
          FROM TB_ATCH_FILE WHERE length(FILE_NM) > 300
        UNION ALL
        SELECT 'TB_ATCH_FILE.NOTI_TTL(' || length(NOTI_TTL) || '자): ' || NOTI_TTL
          FROM TB_ATCH_FILE WHERE length(NOTI_TTL) > 500
        UNION ALL
        SELECT 'TB_ATCH_FILE.NOTI_NO(' || length(NOTI_NO) || '자): ' || NOTI_NO
          FROM TB_ATCH_FILE WHERE length(NOTI_NO) > 50
        UNION ALL
        SELECT 'TB_ATCH_IMG.FILE_PATH(' || length(FILE_PATH) || '자): ' || FILE_PATH
          FROM TB_ATCH_IMG WHERE length(FILE_PATH) > 1000
    ) t;

    IF offender IS NOT NULL THEN
        RAISE EXCEPTION E'표준도메인 길이를 넘는 값이 있어 이관을 중단합니다:\n  %\n'
            '값을 줄이거나 db/standard_terms.json에서 해당 도메인 길이를 늘린 뒤 다시 실행하세요.',
            offender;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 1. 표준도메인
-- ---------------------------------------------------------------------------
-- 분류어 하나가 도메인 하나를 지시한다. 지금 쓰지 않는 분류어까지 만들어 두는 이유는,
-- 다음 컬럼을 보탤 때 타입을 새로 고민하는 순간 표준이 무너지기 때문이다.
CREATE DOMAIN D_SN   AS INTEGER;
CREATE DOMAIN D_NO   AS VARCHAR(50);
CREATE DOMAIN D_CD   AS VARCHAR(30);
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

COMMENT ON DOMAIN D_SN   IS '일련번호 — 숫자 순번. 기관·게시물·첨부·이미지·처분·반복이 모두 이 도메인이다';
COMMENT ON DOMAIN D_NO   IS '번호 — 문자 번호. "고시 제2026-47호"처럼 접두어가 붙은 원문 표기를 담는다';
COMMENT ON DOMAIN D_CD   IS '코드 — 관행적인 8자가 아니라 30자다. 공고종류코드가 뜻이 읽히는 조합형이고 최장값이 MIXED_CHG_PRMSN_CNSLT_APRV(26자)다';
COMMENT ON DOMAIN D_NM   IS '명 — 이름. 첨부파일명이 공고 제목을 그대로 쓰는 경우가 있어 300으로 잡았다';
COMMENT ON DOMAIN D_TTL  IS '제목 — 고시문 제목은 한 문장을 통째로 쓰는 일이 잦아 명보다 길게 잡는다';
COMMENT ON DOMAIN D_DT   IS '일자 — 날짜만. 시각이 필요하면 일시(D_DTM)를 쓴다';
COMMENT ON DOMAIN D_DTM  IS '일시 — 타임존 포함. 감사 컬럼이 이 도메인이다';
COMMENT ON DOMAIN D_YN   IS '여부 — BOOLEAN을 쓰지 않는다. 분류어 YN이 지시하는 도메인이 CHAR(1)인데 실제 타입이 BOOLEAN이면 "분류어만 보고 타입을 안다"는 규칙이 무너진다';
COMMENT ON DOMAIN D_CTNT IS '내용 — 길이를 예측할 수 없는 자유 텍스트';
COMMENT ON DOMAIN D_PATH IS '경로 — 파일 시스템 절대경로';

-- ---------------------------------------------------------------------------
-- 2. 코드값 표준화 (타입을 바꾸기 전에, 아직 TEXT일 때 끝낸다)
-- ---------------------------------------------------------------------------
UPDATE TB_AGNCY SET KND_CD = CASE KND_CD
    WHEN 'mof' THEN 'MOF' WHEN 'local' THEN 'LOCL'
    WHEN 'central' THEN 'CNTL' WHEN 'gazette' THEN 'GZT' ELSE KND_CD END;

UPDATE TB_NOTI SET BOARD_CD = CASE BOARD_CD
    WHEN '게시중' THEN 'POST' WHEN '게시완료' THEN 'CLSD' ELSE BOARD_CD END;

UPDATE TB_ATCH_FILE SET STTS_CD = CASE STTS_CD
    WHEN 'ok' THEN 'OK' WHEN 'failed' THEN 'FAIL'
    WHEN 'skipped' THEN 'SKIP' ELSE STTS_CD END;

UPDATE TB_ATCH_FILE SET FAIL_STEP = CASE FAIL_STEP
    WHEN '판별' THEN 'DTCT' WHEN '추출' THEN 'EXTC'
    WHEN '표해석' THEN 'TBIT' WHEN '매핑' THEN 'MAPP' ELSE FAIL_STEP END;

UPDATE TB_NOTI_ITEM SET VAL_TY_CD = CASE VAL_TY_CD
    WHEN 'text' THEN 'TEXT' WHEN 'date' THEN 'DATE'
    WHEN 'date_range' THEN 'DTRG' WHEN 'number' THEN 'NUM' ELSE VAL_TY_CD END;

-- ---------------------------------------------------------------------------
-- 3. 표현식 인덱스 제거 (컬럼 타입을 바꾸기 전에)
-- ---------------------------------------------------------------------------
-- 평범한 btree는 ALTER COLUMN TYPE이 알아서 다시 만들지만, 함수 표현식 인덱스는 함수 인자
-- 해석이 다시 걸린다. 지우고 새 이름으로 다시 만드는 편이 확실하다.
DROP INDEX IF EXISTS IX_TB_NOTI_ITEM_VAL_WORK_PERIOD;

-- ---------------------------------------------------------------------------
-- 4. 테이블 개명
-- ---------------------------------------------------------------------------
ALTER TABLE TB_AGNCY          RENAME TO AGNCY_BAS;
ALTER TABLE TB_NOTI           RENAME TO NOTI_BAS;
ALTER TABLE TB_NOTI_KND       RENAME TO NOTI_KND_TC;
ALTER TABLE TB_NOTI_ITEM      RENAME TO NOTI_ITEM_TC;
ALTER TABLE TB_ATCH_FILE      RENAME TO ATCH_FILE_DTL;
ALTER TABLE TB_ATCH_IMG       RENAME TO ATCH_IMG_DTL;
ALTER TABLE TB_NOTI_ITEM_VAL  RENAME TO NOTI_ITEM_VAL_DTL;

-- ---------------------------------------------------------------------------
-- 5. 컬럼 개명
-- ---------------------------------------------------------------------------
-- 기관 — AGNCY_NO는 문자 번호가 아니라 AgencyRegistry가 발급하는 숫자 순번이므로
-- 분류어를 NO(문자 번호)에서 SN(일련번호)으로 바로잡는다.
ALTER TABLE AGNCY_BAS RENAME COLUMN AGNCY_NO TO AGNCY_SN;
ALTER TABLE AGNCY_BAS RENAME COLUMN KND_CD   TO AGNCY_KND_CD;

ALTER TABLE NOTI_BAS  RENAME COLUMN AGNCY_NO TO AGNCY_SN;
ALTER TABLE NOTI_BAS  RENAME COLUMN BOARD_CD TO BBS_STTS_CD;

-- UPPER_는 SQL 함수명과 겹친다. 상위(HRNK)로 바꾸고 수식어도 채운다.
ALTER TABLE NOTI_KND_TC RENAME COLUMN UPPER_KND_NM TO HRNK_NOTI_KND_NM;

ALTER TABLE NOTI_ITEM_TC RENAME COLUMN ITEM_CD   TO NOTI_ITEM_CD;
ALTER TABLE NOTI_ITEM_TC RENAME COLUMN ITEM_NM   TO NOTI_ITEM_NM;
ALTER TABLE NOTI_ITEM_TC RENAME COLUMN SRS_NM    TO ITEM_SRS_NM;
ALTER TABLE NOTI_ITEM_TC RENAME COLUMN VAL_TY_CD TO ITEM_VAL_TY_CD;
ALTER TABLE NOTI_ITEM_TC RENAME COLUMN CORE_YN   TO CORE_ITEM_YN;

ALTER TABLE ATCH_FILE_DTL RENAME COLUMN FILE_NM    TO ATCH_FILE_NM;
ALTER TABLE ATCH_FILE_DTL RENAME COLUMN STTS_CD    TO PROC_STTS_CD;
ALTER TABLE ATCH_FILE_DTL RENAME COLUMN FAIL_STEP  TO FAIL_STEP_CD;
ALTER TABLE ATCH_FILE_DTL RENAME COLUMN FAIL_KND   TO FAIL_KND_CD;
ALTER TABLE ATCH_FILE_DTL RENAME COLUMN FAIL_MSG   TO FAIL_MSG_CTNT;
ALTER TABLE ATCH_FILE_DTL RENAME COLUMN EXCL_RSN   TO EXCL_RSN_CTNT;
ALTER TABLE ATCH_FILE_DTL RENAME COLUMN FILE_EXTN  TO FILE_EXTN_NM;
-- REAL_은 PostgreSQL의 부동소수 타입명이라 표준단어에서 뺐다. 실제 = ACTL.
ALTER TABLE ATCH_FILE_DTL RENAME COLUMN REAL_EXTN  TO ACTL_FILE_EXTN_NM;
ALTER TABLE ATCH_FILE_DTL RENAME COLUMN ENGN_NM    TO EXTC_ENGN_NM;
-- YMD는 사전에 없는 임의 약어다. 일자 분류어는 DT이고, 그래야 일시(DTM)와 갈린다.
ALTER TABLE ATCH_FILE_DTL RENAME COLUMN NOTI_YMD   TO NOTI_DT;
-- REG_DT는 TIMESTAMPTZ인데 이름은 일자(DT)였다 — 분류어가 타입을 잘못 지시하고 있었다.
ALTER TABLE ATCH_FILE_DTL RENAME COLUMN REG_DT     TO FRST_REG_DTM;

ALTER TABLE ATCH_IMG_DTL RENAME COLUMN IMG_CPTN  TO IMG_CPTN_CTNT;
ALTER TABLE ATCH_IMG_DTL RENAME COLUMN FILE_PATH TO IMG_FILE_PATH;

ALTER TABLE NOTI_ITEM_VAL_DTL RENAME COLUMN ITEM_CD  TO NOTI_ITEM_CD;
ALTER TABLE NOTI_ITEM_VAL_DTL RENAME COLUMN ITEM_VAL TO ITEM_VAL_CTNT;

-- ---------------------------------------------------------------------------
-- 6. 표준도메인 적용
-- ---------------------------------------------------------------------------
ALTER TABLE AGNCY_BAS
    ALTER COLUMN AGNCY_SN     TYPE D_SN  USING AGNCY_SN::D_SN,
    ALTER COLUMN AGNCY_NM     TYPE D_NM  USING AGNCY_NM::D_NM,
    ALTER COLUMN AGNCY_KND_CD TYPE D_CD  USING AGNCY_KND_CD::D_CD;

ALTER TABLE NOTI_BAS
    ALTER COLUMN NOTI_SN     TYPE D_SN USING NOTI_SN::D_SN,
    ALTER COLUMN AGNCY_SN    TYPE D_SN USING AGNCY_SN::D_SN,
    ALTER COLUMN BBS_STTS_CD TYPE D_CD USING BBS_STTS_CD::D_CD;

ALTER TABLE NOTI_KND_TC
    ALTER COLUMN NOTI_KND_CD      TYPE D_CD USING NOTI_KND_CD::D_CD,
    ALTER COLUMN NOTI_KND_NM      TYPE D_NM USING NOTI_KND_NM::D_NM,
    ALTER COLUMN HRNK_NOTI_KND_NM TYPE D_NM USING HRNK_NOTI_KND_NM::D_NM;

ALTER TABLE NOTI_ITEM_TC ALTER COLUMN CORE_ITEM_YN DROP DEFAULT;
ALTER TABLE NOTI_ITEM_TC
    ALTER COLUMN NOTI_ITEM_CD   TYPE D_CD USING NOTI_ITEM_CD::D_CD,
    ALTER COLUMN NOTI_ITEM_NM   TYPE D_NM USING NOTI_ITEM_NM::D_NM,
    ALTER COLUMN ITEM_SRS_NM    TYPE D_NM USING ITEM_SRS_NM::D_NM,
    ALTER COLUMN ITEM_VAL_TY_CD TYPE D_CD USING ITEM_VAL_TY_CD::D_CD,
    ALTER COLUMN CORE_ITEM_YN   TYPE D_YN USING (CASE WHEN CORE_ITEM_YN THEN 'Y' ELSE 'N' END)::D_YN;
ALTER TABLE NOTI_ITEM_TC ALTER COLUMN CORE_ITEM_YN SET DEFAULT 'N';

ALTER TABLE ATCH_FILE_DTL ALTER COLUMN SCAN_YN DROP DEFAULT;
ALTER TABLE ATCH_FILE_DTL
    ALTER COLUMN NOTI_SN           TYPE D_SN   USING NOTI_SN::D_SN,
    ALTER COLUMN ATCH_SN           TYPE D_SN   USING ATCH_SN::D_SN,
    ALTER COLUMN ATCH_FILE_NM      TYPE D_NM   USING ATCH_FILE_NM::D_NM,
    ALTER COLUMN PROC_STTS_CD      TYPE D_CD   USING PROC_STTS_CD::D_CD,
    ALTER COLUMN FAIL_STEP_CD      TYPE D_CD   USING FAIL_STEP_CD::D_CD,
    ALTER COLUMN FAIL_KND_CD       TYPE D_CD   USING FAIL_KND_CD::D_CD,
    ALTER COLUMN FAIL_MSG_CTNT     TYPE D_CTNT USING FAIL_MSG_CTNT::D_CTNT,
    ALTER COLUMN EXCL_RSN_CTNT     TYPE D_CTNT USING EXCL_RSN_CTNT::D_CTNT,
    ALTER COLUMN FILE_EXTN_NM      TYPE D_NM   USING FILE_EXTN_NM::D_NM,
    ALTER COLUMN ACTL_FILE_EXTN_NM TYPE D_NM   USING ACTL_FILE_EXTN_NM::D_NM,
    ALTER COLUMN SCAN_YN           TYPE D_YN   USING (CASE WHEN SCAN_YN THEN 'Y' ELSE 'N' END)::D_YN,
    ALTER COLUMN EXTC_ENGN_NM      TYPE D_NM   USING EXTC_ENGN_NM::D_NM,
    ALTER COLUMN NOTI_KND_CD       TYPE D_CD   USING NOTI_KND_CD::D_CD,
    ALTER COLUMN NOTI_NO           TYPE D_NO   USING NOTI_NO::D_NO,
    ALTER COLUMN NOTI_DT           TYPE D_DT   USING NOTI_DT::D_DT,
    ALTER COLUMN NOTI_TTL          TYPE D_TTL  USING NOTI_TTL::D_TTL,
    ALTER COLUMN FRST_REG_DTM      TYPE D_DTM  USING FRST_REG_DTM::D_DTM;
ALTER TABLE ATCH_FILE_DTL ALTER COLUMN SCAN_YN SET DEFAULT 'N';

ALTER TABLE ATCH_IMG_DTL
    ALTER COLUMN NOTI_SN       TYPE D_SN   USING NOTI_SN::D_SN,
    ALTER COLUMN ATCH_SN       TYPE D_SN   USING ATCH_SN::D_SN,
    ALTER COLUMN IMG_SN        TYPE D_SN   USING IMG_SN::D_SN,
    ALTER COLUMN IMG_CPTN_CTNT TYPE D_CTNT USING IMG_CPTN_CTNT::D_CTNT,
    ALTER COLUMN IMG_FILE_PATH TYPE D_PATH USING IMG_FILE_PATH::D_PATH;

ALTER TABLE NOTI_ITEM_VAL_DTL
    ALTER COLUMN NOTI_SN       TYPE D_SN   USING NOTI_SN::D_SN,
    ALTER COLUMN ATCH_SN       TYPE D_SN   USING ATCH_SN::D_SN,
    ALTER COLUMN DSPS_SN       TYPE D_SN   USING DSPS_SN::D_SN,
    ALTER COLUMN NOTI_ITEM_CD  TYPE D_CD   USING NOTI_ITEM_CD::D_CD,
    ALTER COLUMN RPT_SN        TYPE D_SN   USING RPT_SN::D_SN,
    ALTER COLUMN ITEM_VAL_CTNT TYPE D_CTNT USING ITEM_VAL_CTNT::D_CTNT;

-- ---------------------------------------------------------------------------
-- 7. 감사 컬럼
-- ---------------------------------------------------------------------------
-- 전 테이블 같은 이름이다. ATCH_FILE_DTL만 REG_DT를 물려받아 FRST_REG_DTM이 이미 있다.
ALTER TABLE AGNCY_BAS         ADD COLUMN FRST_REG_DTM D_DTM NOT NULL DEFAULT now();
ALTER TABLE NOTI_BAS          ADD COLUMN FRST_REG_DTM D_DTM NOT NULL DEFAULT now();
ALTER TABLE NOTI_KND_TC       ADD COLUMN FRST_REG_DTM D_DTM NOT NULL DEFAULT now();
ALTER TABLE NOTI_ITEM_TC      ADD COLUMN FRST_REG_DTM D_DTM NOT NULL DEFAULT now();
ALTER TABLE ATCH_IMG_DTL      ADD COLUMN FRST_REG_DTM D_DTM NOT NULL DEFAULT now();
ALTER TABLE NOTI_ITEM_VAL_DTL ADD COLUMN FRST_REG_DTM D_DTM NOT NULL DEFAULT now();

ALTER TABLE AGNCY_BAS         ADD COLUMN LAST_CHG_DTM D_DTM NOT NULL DEFAULT now();
ALTER TABLE NOTI_BAS          ADD COLUMN LAST_CHG_DTM D_DTM NOT NULL DEFAULT now();
ALTER TABLE NOTI_KND_TC       ADD COLUMN LAST_CHG_DTM D_DTM NOT NULL DEFAULT now();
ALTER TABLE NOTI_ITEM_TC      ADD COLUMN LAST_CHG_DTM D_DTM NOT NULL DEFAULT now();
ALTER TABLE ATCH_FILE_DTL     ADD COLUMN LAST_CHG_DTM D_DTM NOT NULL DEFAULT now();
ALTER TABLE ATCH_IMG_DTL      ADD COLUMN LAST_CHG_DTM D_DTM NOT NULL DEFAULT now();
ALTER TABLE NOTI_ITEM_VAL_DTL ADD COLUMN LAST_CHG_DTM D_DTM NOT NULL DEFAULT now();

-- ---------------------------------------------------------------------------
-- 8. 인덱스 개명
-- ---------------------------------------------------------------------------
-- 이름에서 TB_를 빼고 테이블 약칭을 쓴다. 30바이트 관행은 테이블·컬럼에 적용되는 것이고
-- 인덱스는 질의에 등장하지 않지만, 길어서 좋을 것도 없다.
ALTER INDEX IF EXISTS IX_TB_NOTI_AGNCY_NO       RENAME TO IX_NOTI_BAS_AGNCY_SN;
ALTER INDEX IF EXISTS IX_TB_ATCH_FILE_STTS_CD   RENAME TO IX_ATCH_FILE_PROC_STTS_CD;
ALTER INDEX IF EXISTS IX_TB_ATCH_FILE_NOTI_KND  RENAME TO IX_ATCH_FILE_NOTI_KND_CD;
ALTER INDEX IF EXISTS IX_TB_ATCH_FILE_NOTI_YMD  RENAME TO IX_ATCH_FILE_NOTI_DT;
ALTER INDEX IF EXISTS IX_TB_ATCH_FILE_REAL_EXTN RENAME TO IX_ATCH_FILE_ACTL_EXTN_NM;
ALTER INDEX IF EXISTS IX_TB_NOTI_ITEM_VAL_ITEM_CD RENAME TO IX_ITEM_VAL_NOTI_ITEM_CD;
ALTER INDEX IF EXISTS IX_TB_NOTI_ITEM_VAL_VAL     RENAME TO IX_ITEM_VAL_ITEM_VAL;

-- 3에서 지운 기간 인덱스를 새 이름으로 다시 만든다. 근거는 V1의 원 주석 그대로다 —
-- 값을 date로 캐스팅하면 STABLE이라 인덱스에 쓸 수 없고, 정규식 부분 인덱스는 플래너가
-- 함의를 증명하지 못해 선택되지 않는다.
CREATE INDEX IF NOT EXISTS IX_ITEM_VAL_WORK_PERIOD
    ON NOTI_ITEM_VAL_DTL USING gist (iso_daterange(ITEM_VAL_CTNT))
    WHERE NOTI_ITEM_CD = 'WORK_PERIOD';

-- ---------------------------------------------------------------------------
-- 9. 제약조건 개명
-- ---------------------------------------------------------------------------
-- V1이 이름을 주지 않아 PostgreSQL이 tb_agncy_pkey 같은 이름을 자동 발급했고, 테이블을
-- 개명해도 제약조건 이름은 따라오지 않아 옛 이름이 남는다. 카탈로그에서 실제 이름을 읽어
-- 바꾼다 — 자동 발급 규칙을 손으로 재현하면 하나만 어긋나도 이관이 통째로 죽는다.
-- 제약조건 이름은 질의에 등장하지 않으므로 30바이트 관행을 적용하지 않는다.
DO $$
DECLARE
    c record;
BEGIN
    FOR c IN
        SELECT cls.relname AS tbl,
               con.conname,
               con.contype,
               ref.relname AS reftbl
          FROM pg_constraint con
          JOIN pg_class cls     ON cls.oid = con.conrelid
          JOIN pg_namespace ns  ON ns.oid = cls.relnamespace
          LEFT JOIN pg_class ref ON ref.oid = con.confrelid
         WHERE ns.nspname = current_schema()
           AND con.contype IN ('p', 'f')
           AND cls.relname IN ('agncy_bas', 'noti_bas', 'noti_knd_tc', 'noti_item_tc',
                               'atch_file_dtl', 'atch_img_dtl', 'noti_item_val_dtl')
         ORDER BY cls.relname, con.contype, con.conname
    LOOP
        EXECUTE format('ALTER TABLE %I RENAME CONSTRAINT %I TO %I',
                       c.tbl, c.conname,
                       CASE WHEN c.contype = 'p'
                            THEN 'pk_' || c.tbl
                            ELSE 'fk_' || c.tbl || '__' || c.reftbl END);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 10. 주석 재발행
-- ---------------------------------------------------------------------------
-- 주석은 개명을 따라오지만 그 본문에 옛 이름이 박혀 있다. 본문이 옛 이름을 가리키면
-- 주석을 읽고 질의를 짜는 사람이 없는 테이블을 찾게 된다.
COMMENT ON TABLE AGNCY_BAS IS '기관 — 고시·공고를 수집한 기관 게시판. 입력 폴더 하나가 한 행이다';
COMMENT ON COLUMN AGNCY_BAS.AGNCY_SN IS
    '기관일련번호. 입력 폴더명 앞 번호이며, 한 번호 아래 이름이 갈리면(전자관보) 그 번호는 비우고 최대번호 뒤로 새로 발급한다 — AgencyRegistry가 폴더 집합 전체를 보고 정하므로 같은 입력이면 매번 같은 값이다';
COMMENT ON COLUMN AGNCY_BAS.AGNCY_NM IS
    '기관명 — 입력 폴더명에서 온다. 문서 본문의 발령 기관명은 매핑 단계가 읽기는 하지만 적재하지 않는다(기관 추적은 이 컬럼으로 충분하다)';
COMMENT ON COLUMN AGNCY_BAS.AGNCY_KND_CD IS
    '기관종류코드(CD_AGNCY_KND): MOF 지방해양수산청 / LOCL 지방자치단체 / CNTL 중앙행정기관 / GZT 전자관보. GZT는 기관 종류가 아니라 수집 경로다 — 같은 부처를 자체 게시판(CNTL)과 전자관보 양쪽에서 긁으므로, 이름이 같은 두 행을 이 값으로 가린다';

COMMENT ON TABLE NOTI_BAS IS
    '고시공고게시물 — 크롤링 대상이 첨부파일뿐이라 게시물 자체의 정보는 없고, 같은 게시물의 첨부를 묶는 키로만 쓴다';
COMMENT ON COLUMN NOTI_BAS.NOTI_SN IS
    '고시공고일련번호 = 크롤 순번. 파일명 "{순번}_{제목}" 또는 "{순번}_{첨부순번}_{제목}"의 앞자리다. 기관마다 연속 블록을 차지하고 기관 간 충돌이 없어 기관 스코프가 필요 없다. 문서 본문의 고시번호(ATCH_FILE_DTL.NOTI_NO)와는 다른 것이다. 크롤 산출물이 아닌 파일은 음수를 발급받아 크롤 순번과 겹치지 않는다';
COMMENT ON COLUMN NOTI_BAS.AGNCY_SN IS
    '기관일련번호 — 입력 폴더명에서 채운다. 첨부파일 안에는 수집처가 없어 폴더가 유일한 근거다. 폴더 규약 밖에서 온 파일(수동 수집분, 입력 루트 직속)은 NULL이다';
COMMENT ON COLUMN NOTI_BAS.BBS_STTS_CD IS
    '게시상태코드(CD_BBS_STTS): POST 게시중 / CLSD 게시완료. 폴더명 꼬리표로 가른다 — "지난·이전·완료"류면 CLSD, 꼬리표가 없거나 "게시중·고시공고"류면 POST. 같은 기관이 게시판을 둘 운영하면(12_1 / 12_2) 기관은 하나고 이 값만 갈린다';

COMMENT ON TABLE NOTI_KND_TC IS
    '공고종류 56종. 한 기관이 평균 12종을 발행하므로 기관으로는 종류를 구분할 수 없다';
COMMENT ON COLUMN NOTI_KND_TC.NOTI_KND_CD IS
    '공고종류코드 — {상위분류}_{행위} 조합(OCUPY_PRMSN = 점용·사용 허가). 상위분류는 HRNK_NOTI_KND_NM에도 있지만 코드만 보고도 계열을 알 수 있어야 필터가 쉽다';
COMMENT ON COLUMN NOTI_KND_TC.HRNK_NOTI_KND_NM IS
    '상위공고종류명 — 점용·사용 / 실시계획 / 매립 / 점용료·사용료 / 혼합 / 기타. UPPER_로 시작하면 SQL 함수명과 겹쳐 상위(HRNK)를 쓴다';

COMMENT ON TABLE NOTI_ITEM_TC IS
    '공고항목 40종. synonyms.json이 단일 정의처이며 ReferenceSync가 기동 시 upsert한다';
COMMENT ON COLUMN NOTI_ITEM_TC.NOTI_ITEM_CD IS
    '공고항목코드 = synonyms.json의 canonical. 컬럼명이 아니라 행으로 쌓이는 코드 데이터이므로 표준단어로 분해되지 않는다';
COMMENT ON COLUMN NOTI_ITEM_TC.ITEM_SRS_NM IS
    '항목계열명 — 같은 뜻을 문맥별로 다르게 부르는 항목들의 묶음. 준공·완료 수리 문서에는 점용·사용 면적이 아니라 준공면적이 오므로, 누락 검증은 항목이 아니라 계열 단위로 봐야 오탐이 없다';
COMMENT ON COLUMN NOTI_ITEM_TC.ITEM_VAL_TY_CD IS
    '항목값유형코드(CD_ITEM_VAL_TY): TEXT 원문 보존 / DATE ISO 정규화 / DTRG daterange / NUM 수치';
COMMENT ON COLUMN NOTI_ITEM_TC.CORE_ITEM_YN IS
    '주요항목여부 — 전수 표본 출현율 60% 이상인 주요 6항목. 누락 검증 가중치용이며 저장 위치와는 무관하다';

COMMENT ON TABLE ATCH_FILE_DTL IS
    '첨부파일 — 파일 1건. 문서 단위 메타(고시번호·고시일자·제목)와 추출 상태';
COMMENT ON COLUMN ATCH_FILE_DTL.ATCH_SN IS
    '첨부일련번호. 첨부가 1건인 게시물은 파일명에 순번이 없어 항상 1이다. 결번은 버그가 아니라 미수집 신호이므로 다시 매겨 메우지 않는다';
COMMENT ON COLUMN ATCH_FILE_DTL.PROC_STTS_CD IS
    '처리상태코드(CD_PROC_STTS): OK 정상 / FAIL 실패 / SKIP 적재제외. FAIL·SKIP도 행으로 남긴다 — 성공만 적재하면 "추출 0건인 기관"이 DB에서 보이지 않는다';
COMMENT ON COLUMN ATCH_FILE_DTL.FAIL_STEP_CD IS
    '실패단계코드(CD_FAIL_STEP): DTCT 판별 / EXTC 추출 / TBIT 표해석 / MAPP 매핑';
COMMENT ON COLUMN ATCH_FILE_DTL.FAIL_KND_CD IS
    '실패종류코드(CD_FAIL_KND) — detect.FailureKind가 정의처다';
COMMENT ON COLUMN ATCH_FILE_DTL.FILE_EXTN_NM IS
    '파일확장자명 — 확장자가 내용과 어긋난 파일이 흔하다. FILE_EXTN_NM <> ACTL_FILE_EXTN_NM인 행이 곧 그 목록이다';
COMMENT ON COLUMN ATCH_FILE_DTL.ACTL_FILE_EXTN_NM IS
    '실제파일확장자명 — 내용(매직바이트)으로 판정한 형식. REAL_은 PostgreSQL 부동소수 타입명과 겹쳐 실제(ACTL)를 쓴다';
COMMENT ON COLUMN ATCH_FILE_DTL.NOTI_KND_CD IS
    '공고종류코드 — 제목으로 판정한다. 규칙은 notice_types.json의 keywords에 있고 NoticeTypes.classify가 판정한다. 제목이 없거나 56종에 자리가 없는 문서는 NULL이다 — 억지로 가장 비슷한 종류에 밀어 넣으면 종류별 집계가 조용히 오염된다';
COMMENT ON COLUMN ATCH_FILE_DTL.NOTI_NO IS
    '고시번호 — 문서 본문의 "고시 제2026-47호". 게시물 일련번호(NOTI_BAS.NOTI_SN)와도, 처분받은 허가번호(NOTI_ITEM_CD=''APPROVAL_NO'')와도 다른 것이다. 전수에서 고시번호와 허가번호가 같은 행은 0/1,067이다';
COMMENT ON COLUMN ATCH_FILE_DTL.NOTI_DT IS
    '고시일자. 일자(DT)와 일시(DTM)를 분류어로 가르므로 이 컬럼은 DATE이고 FRST_REG_DTM은 TIMESTAMPTZ다';
COMMENT ON COLUMN ATCH_FILE_DTL.FRST_REG_DTM IS '최초등록일시 — 이 행을 처음 적재한 시각';
COMMENT ON COLUMN ATCH_FILE_DTL.LAST_CHG_DTM IS '최종변경일시 — 이 행을 마지막으로 갱신한 시각';

COMMENT ON TABLE ATCH_IMG_DTL IS
    '첨부이미지 — 이미지는 처분 레코드가 아니라 첨부파일의 속성이다. 한 파일이 레코드 N건을 낳을 때 어느 레코드에 붙일지 정할 근거가 없다';
COMMENT ON COLUMN ATCH_IMG_DTL.IMG_FILE_PATH IS '이미지파일경로 — 추출한 이미지를 저장한 절대경로';

COMMENT ON TABLE NOTI_ITEM_VAL_DTL IS
    '공고항목값 — 40개 표준항목을 전부 동등하게 행으로 담는다';
COMMENT ON COLUMN NOTI_ITEM_VAL_DTL.DSPS_SN IS
    '처분일련번호 = 목록표의 N번째 행. 단일 서식 문서는 항상 1. 이 컬럼이 없으면 목록표에서 장소·면적·성명이 짝을 잃고, 그 손실은 조용하며 사후 복구가 불가능하다';
COMMENT ON COLUMN NOTI_ITEM_VAL_DTL.RPT_SN IS
    '반복일련번호 — 같은 처분에 같은 항목이 반복될 때의 순번. 변경 고시문의 당초/변경 대비표가 대표적이다';
COMMENT ON COLUMN NOTI_ITEM_VAL_DTL.ITEM_VAL_CTNT IS
    '항목값내용 — Java가 정규화한 값. 날짜는 ISO, 기간은 daterange 리터럴, 정규화 실패나 TEXT 항목은 원문 그대로다. NOTI_ITEM_TC.ITEM_VAL_TY_CD와 대조하면 실패 여부를 가릴 수 있다';

COMMENT ON FUNCTION iso_daterange(text) IS
    'NOTI_ITEM_VAL_DTL.ITEM_VAL_CTNT의 "[시작,종료]" 리터럴을 daterange로. 형식이 어긋나면 NULL. 인덱스 표현식에 쓰려고 IMMUTABLE로 선언했고, ISO 고정폭 표기는 DateStyle과 무관해 그 선언이 참이다';
