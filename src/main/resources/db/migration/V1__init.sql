-- 문서 원본 테이블: 시퀀스 + 표준 필드
CREATE TABLE IF NOT EXISTS documents (
    seq                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- 시퀀스
    source_file        TEXT NOT NULL,        -- 원본 파일명
    file_type          TEXT NOT NULL,        -- hwp / hwpx / hml / pdf
    is_scanned         BOOLEAN NOT NULL DEFAULT FALSE,
    engine             TEXT,                 -- hwplib / owpml / hml-dom / pdfbox / paddleocr-vl
    agency             TEXT,
    notice_no          TEXT,
    notice_date        DATE,                 -- ISO 날짜 (native DATE 타입)
    title              TEXT,
    signer             TEXT,
    approval_no        TEXT,
    approval_date      DATE,
    location           TEXT,
    area               TEXT,
    work_description   TEXT,
    work_period_start  DATE,
    work_period_end    DATE,
    applicant_name     TEXT,
    applicant_address  TEXT,
    remarks            TEXT,
    extras             JSONB,                -- 매핑 안 된 라벨:값 (native JSONB)
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_documents_source_file ON documents(source_file);
CREATE INDEX IF NOT EXISTS idx_documents_agency      ON documents(agency);
CREATE INDEX IF NOT EXISTS idx_documents_extras_gin  ON documents USING GIN (extras);

-- 참조 파일 테이블: 참조 시퀀스 + 이미지명 + 저장 경로
CREATE TABLE IF NOT EXISTS ref_files (
    seq           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_seq  BIGINT NOT NULL REFERENCES documents(seq) ON DELETE CASCADE,  -- 참조 시퀀스
    image_name    TEXT NOT NULL,   -- 이미지명
    file_path     TEXT NOT NULL    -- 저장 경로 (images/ 폴더 내)
);
CREATE INDEX IF NOT EXISTS idx_ref_files_doc ON ref_files(document_seq);
