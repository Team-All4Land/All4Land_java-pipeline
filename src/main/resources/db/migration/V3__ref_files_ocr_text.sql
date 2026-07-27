-- 문서에 삽입된 사진·위치도의 내용을 이미지별로 보관한다.
-- 본문 대부분이 사진인 고시(붙임 현장사진, 위치도)는 그 텍스트가 이미지 안에만 있어
-- 지금까지 이미지명·경로만 남고 내용은 어디에도 적재되지 않았다.
ALTER TABLE ref_files ADD COLUMN IF NOT EXISTS ocr_text TEXT;
COMMENT ON COLUMN ref_files.ocr_text IS '이미지에서 OCR(PaddleOCR-VL)로 읽은 텍스트. 읽지 않았거나 글자가 없으면 NULL';

-- documents.engine 값 의미 확장: 이미지 OCR이 본문에 기여하면 '+paddleocr-vl'이 붙는다
-- (예: hwplib+paddleocr-vl). 컬럼 타입(TEXT)은 그대로이며 저장되는 값의 형태만 늘어난다.
COMMENT ON COLUMN documents.engine IS '추출 엔진. 이미지 OCR이 본문에 기여하면 +paddleocr-vl이 붙는다';
