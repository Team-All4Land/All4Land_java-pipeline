-- ref_files.file_path 의미 변경: outputDir 기준 상대경로 → 저장 이미지의 절대경로.
-- 컬럼 타입(TEXT)은 그대로이며 저장되는 값의 의미만 바뀐다(애플리케이션이 절대경로를 기록).
-- 리눅스 서버에서 이미지 파일을 절대경로로 참조하기 위한 변경이다.
COMMENT ON COLUMN ref_files.file_path IS '저장 이미지의 절대경로';
COMMENT ON COLUMN ref_files.image_name IS '이미지명';
