-- TB_ATCH_FILE.DSPS_CNT 제거.
--
-- 이 컬럼이 담던 값은 전부 자식 테이블 질의로 나온다:
--   처분이 몇 건인가            → COUNT(DISTINCT TB_NOTI_ITEM_VAL.DSPS_SN)
--   값을 하나도 못 넣은 첨부    → STTS_CD='ok'인데 LEFT JOIN 결과가 0건
--
-- 원래 주석은 "목록표 행 중 값을 하나도 못 읽은 행을 감지"한다고 적혀 있었지만, 그런 행은
-- TableInterpreter가 레코드로 만들기 전에 버려서(facts가 비면 행을 담지 않는다) 이 컬럼에도
-- 잡히지 않았다. 유도할 수 없는 값이 하나도 없으므로, 적재 로직과 계속 동기화할 이유가 없다.
ALTER TABLE TB_ATCH_FILE DROP COLUMN IF EXISTS DSPS_CNT;

-- 공고종류가 55종에서 56종이 됐다(ETC_BID_NOTI 입찰공고). V1의 주석 문구는 이미 적용된
-- 마이그레이션이라 손대지 않고 여기서 다시 단다 — 파일을 고치면 Flyway 체크섬이 깨진다.
COMMENT ON TABLE TB_NOTI_KND IS
    '공고종류 56종. 한 기관이 평균 12종을 발행하므로 기관으로는 종류를 구분할 수 없다';
COMMENT ON COLUMN TB_ATCH_FILE.NOTI_KND_CD IS
    '제목으로 판정한 공고종류. 규칙은 notice_types.json의 keywords에 있고 NoticeTypes.classify가 판정한다. 제목을 못 읽었거나 어느 종류에도 자리가 없는 문서는 NULL이다 — 억지로 가장 비슷한 종류에 밀어 넣으면 종류별 집계가 조용히 오염된다';
