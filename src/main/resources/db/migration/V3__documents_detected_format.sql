-- file_type은 파일명 확장자를 그대로 유지하고, 파일 내용(매직바이트)으로 판정한 실제 형식을
-- detected_format에 따로 남긴다. 확장자가 어긋난 파일(예: HWPX를 .hwp로 저장)이 흔한데,
-- file_type을 실제 형식으로 덮어쓰면 기존 적재 데이터·집계 쿼리와 값이 어긋나기 때문이다.
--
-- 두 값이 다른 행이 곧 "확장자가 내용과 어긋난 파일" 목록이다:
--   SELECT source_file, file_type, detected_format
--     FROM documents WHERE detected_format IS NOT NULL AND file_type <> detected_format;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS detected_format TEXT;

COMMENT ON COLUMN documents.file_type IS '파일명 확장자 기준 형식: hwp / hwpx / hml / pdf';
COMMENT ON COLUMN documents.detected_format IS '내용으로 판정한 실제 형식: hwp / hwp3 / hwpx / hml / pdf';

CREATE INDEX IF NOT EXISTS idx_documents_detected_format ON documents(detected_format);
