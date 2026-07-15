# 프로젝트 구조 — Java 기반 (PROJECT_STRUCTURE_JAVA)

공유수면 점용·사용 고시류 문서(**HWP / HWPX / HML / PDF — 네이티브·스캔본 모두**)에서
텍스트·표·이미지를 추출하고, 표준 필드 스키마로 정규화한 뒤 **PostgreSQL DB에 적재**하는
파이프라인의 **Java 구현** 구조.

파이프라인 흐름·계약(raw JSON, 표준 스키마, DB 2테이블)은 Python 버전과 동일하다.
단, **PaddleOCR-VL은 Java에서 직접 구동할 수 없으므로 Python CLI 스크립트로
분리**하고, Java 파이프라인이 **서브프로세스로 실행**한다.

## 1. 전체 흐름

```
입력 파일 (.hwp / .hwpx / .hml / .pdf)   ← 모든 확장자가 스캔본일 수 있음
        │
        ▼
Java CLI: extract.jar pipeline (배치 진입점, picocli)
        │
        │  [1차 분기] 스캔본 판별 — DetectorRegistry (프로세스 내 호출)
        │
        ├── 스캔본 (확장자 무관) ──► ScanOcrRunner ──서브프로세스──► PaddleOCR-VL CLI (Python)
        │       · PDF        : 원본 PDF 경로를 인자로 전달 → 페이지 렌더링 → VLM
        │       · HWP/HWPX/HML : Java가 임베디드 이미지(BinData)를 추출해
        │                        이미지 경로들만 인자로 전달 → VLM
        │       ◄── 결과: --output 경로의 raw JSON (§4 계약 그대로)
        │
        └── 네이티브 ── [2차 분기] 확장자별 Extractor
                ├─ .hwp   →  HwplibExtractor  (hwplib)
                ├─ .hwpx  →  OwpmlExtractor   (hwpxlib / 자체 OWPML 파싱)
                ├─ .hml   →  HmlExtractor     (StAX, 외부 의존성 없음)
                └─ .pdf   →  PdfBoxExtractor  (Apache PDFBox + 선분 클러스터링 표 탐지)
        │
        ▼
raw JSON (공통 계약 §4 — Python 버전과 동일, Jackson DTO)
        ▼
Mapper.mapToSchema (라벨 정규화 + 동의어 사전 매핑 + 값 정규화)
        ▼
표준 스키마 JSON {"source_file", "records": [...], "images": [...]}
        ▼
DbLoader (PostgreSQL JDBC + HikariCP)
        ▼
PostgreSQL (documents / ref_files 2테이블) + images/ 폴더
```

핵심 원칙 유지: **판별(detect) → 추출(engine) → 매핑(common) → 적재(db) 4계층 분리**.
raw JSON 계약이 Python 버전과 동일하므로, 이행 기간 동안 **같은 픽스처를 두 파이프라인에
넣어 결과를 교차 검증**할 수 있다.

## 2. 디렉터리 트리

```
extract-java/
├── build.gradle                 # Gradle (Java 17+). Maven도 동일 구성 가능
├── settings.gradle
├── input/                       # 배치 기본 입력 폴더
│
├── src/main/java/com/<org>/extract/
│   ├── cli/                     # ★ 진입점 (picocli 서브커맨드)
│   │   ├── Main.java            #   서브커맨드 등록·공통 옵션
│   │   ├── PipelineCommand.java #   pipeline: 판별→추출→매핑→적재 일괄 (기존 pipeline.mjs 대체)
│   │   ├── DetectCommand.java   #   detect: 스캔 여부 일괄 분류 (--json)
│   │   ├── ExtractCommand.java  #   extract: 형식/엔진 지정 추출 (+--raw, --no-images)
│   │   ├── MapCommand.java      #   map: raw JSON → 스키마 JSON (매핑 전용)
│   │   └── LoadCommand.java     #   load: 스키마 JSON → PostgreSQL (적재 전용)
│   │
│   ├── detect/                  # ★ 1차 분기: 스캔 판별
│   │   ├── ScanDetector.java    #   인터페이스: boolean isScanned(Path)
│   │   ├── DetectorRegistry.java#   확장자 → 판별기 매핑 (§3)
│   │   ├── PdfScanDetector.java #   텍스트 레이어 유무 (PDFBox)
│   │   ├── HwpScanDetector.java #   네이티브 텍스트량 vs 임베디드 이미지 비중 (hwplib)
│   │   ├── HwpxScanDetector.java#   ZIP 내 본문 XML 텍스트 검사
│   │   └── HmlScanDetector.java #   XML 본문 텍스트 vs base64 BinData 비중
│   │
│   ├── common/                  # ★ 형식 공통 계층
│   │   ├── model/               #   Jackson DTO — 계약의 단일 정의처
│   │   │   ├── RawDocument.java #     source_file / file_type / is_scanned / content / images
│   │   │   ├── RawParagraph.java, RawTable.java, RawCell.java, RawImage.java
│   │   │   ├── NoticeRecord.java#     15개 표준 필드 + extras (java record)
│   │   │   └── SchemaResult.java#     source_file / records / images
│   │   ├── Synonyms.java        #   LABEL_SYNONYMS 동의어 사전 + normalizeLabel + 괄호 필드 분리
│   │   ├── Mapper.java          #   mapToSchema(RawDocument) → SchemaResult
│   │   ├── Heuristics.java      #   고시문 제목 추정(guessTitleFromTables), 캡션 매칭
│   │   ├── Tables.java          #   그리드 유틸 (cleanGrid, gridToTable)
│   │   └── Address.java         #   주소 추출 휴리스틱
│   │
│   ├── engine/                  # ★ 2차 분기: 확장자별 네이티브 추출 엔진
│   │   ├── Extractor.java       #   인터페이스: supports(ext) / extractRaw(path) / saveImages(path, dir)
│   │   ├── ExtractorRegistry.java
│   │   ├── hwp/
│   │   │   └── HwplibExtractor.java   # hwplib (kr.dogfoot:hwplib) — 문단/표/BinData
│   │   ├── hwpx/
│   │   │   └── OwpmlExtractor.java    # hwpxlib 기반. 인라인 태그(<hp:fwSpace/>) 셀 텍스트
│   │   │                              # 잘림이 없는지 픽스처로 검증, 문제 시 자체 StAX 파싱
│   │   ├── hml/
│   │   │   └── HmlExtractor.java      # StAX 직접 파싱 — base64 BinData 이미지,
│   │   │                              # ColSpan/RowSpan 병합 구조 정확히 복원
│   │   └── pdf/
│   │       ├── PdfBoxExtractor.java   # Apache PDFBox — 텍스트/이미지, 도장 제외 휴리스틱
│   │       └── TableDetector.java     # 선분 클러스터링 표 탐지 (pdfplumber 로직 포팅)
│   │
│   ├── scan/                    # ★ 스캔본 처리 — PaddleOCR-VL CLI 서브프로세스 실행기
│   │   ├── ScanOcrRunner.java   #   ProcessBuilder — CLI 계약(§7)대로 실행, --output JSON 파싱
│   │   └── ScanOcrConfig.java   #   실행 커맨드·스크립트 경로·타임아웃 설정
│   │
│   └── db/                      # ★ 최종 적재 계층
│       ├── DataSourceFactory.java #  HikariCP 커넥션 풀 생성 (application.properties 기반)
│       ├── DbSchema.java        #   DDL(§6) 실행 (Flyway 사용 시 마이그레이션은 resources/db/migration/)
│       └── DbLoader.java        #   SchemaResult → documents/ref_files 적재 (PostgreSQL JDBC)
│
├── src/main/resources/
│   ├── application.properties   # db.url=jdbc:postgresql://... , 풀 설정, ocr.cli.* 등 (설정 단일 출처)
│   └── db/migration/           # Flyway 마이그레이션 (V1__init.sql = §6 DDL, 이후 버전 누적)
│
├── src/test/java/...            # JUnit 5 — detect / mapper / 각 extractor / scan / db
├── src/test/resources/fixtures/ # 실제 고시문 픽스처 (형식·스캔 여부별, Python 버전과 공유)
│
└── ocr-cli/                     # ★★ 유일한 Python 잔존부: PaddleOCR-VL CLI (§7 계약)
    ├── paddleocr_vl_cli.py      #   원본 scan/paddleocr/reader.py 로직 발췌한 독립 실행본
    │                            #   (common/hwp/hwpx 불필요, paddleocr만 있으면 동작)
    ├── requirements.txt         #   paddleocr[doc-parser], paddlepaddle>=3.2.1, pymupdf
    └── README.md                #   설치·호출 규약·연동 안내
```

## 3. 스캔 판별 (detect) 계약

```java
public interface ScanDetector {
    boolean isScanned(Path file);
}
// DetectorRegistry: 확장자 → ScanDetector 매핑. 등록 안 된 확장자는 예외 → [실패] 격리
boolean scanned = DetectorRegistry.isScanned(Path.of("고시문.pdf"));
```

Python 버전과 달리 별도 프로세스 호출이 아니라 **같은 JVM 안에서 판별**하므로
배치 1회 분류 같은 우회가 필요 없다. 판별 기준은 동일:

| 형식 | 스캔본 판별 기준 | 사용 라이브러리 |
|---|---|---|
| PDF | 텍스트 레이어 유무 | PDFBox |
| HWP | 네이티브 본문 텍스트량 대비 임베디드 이미지 비중 | hwplib |
| HWPX | ZIP 내 본문 XML의 텍스트 존재 여부 | java.util.zip + StAX |
| HML | XML 본문 텍스트 대비 base64 BinData 비중 | StAX |

스캔본으로 판별되면 확장자와 무관하게 `ScanOcrRunner`가 PaddleOCR-VL CLI를
서브프로세스로 실행해 처리한다.

## 4. 공통 계약: raw JSON 형식 (Python 버전과 동일)

모든 추출 경로(네이티브 Extractor, OCR CLI 결과)는 아래 형식을 출력해야 한다.
Java에서는 `common/model/RawDocument.java`(Jackson)가 이 계약의 단일 정의처다.

```json
{
  "source_file": "원본파일명.ext",
  "file_type": "hwp",
  "is_scanned": false,
  "content": [
    {"type": "paragraph", "text": "문단 텍스트"},
    {"type": "table", "n_rows": 2, "n_cols": 8,
     "grid": [["행", "별"], ["셀", "텍스트"]],
     "cells": [{"row": 0, "col": 0, "row_span": 1, "col_span": 1, "text": "행"}]}
  ],
  "images": [
    {"name": "고시문_img0.png", "path": "images/고시문_img0.png",
     "size": 1234, "ocr_text": null}
  ]
}
```

- `content`는 문서 등장 순서 유지 (문단·표 혼재).
- 표는 `grid` 필수, `cells`(병합 셀 span)는 선택 — HmlExtractor·OwpmlExtractor가 채운다.
- 이미지 `path`(저장 경로)는 DB 적재(`ref_files`)에 필요하므로 필수.
  `ocr_text`는 선택 필드 — 현재 Java 파이프라인은 채우지 않지만 계약 호환을 위해 유지.
- 도장(관인) 제외: 텍스트 PDF는 소형+붉은색 우세 휴리스틱, 스캔본은 CLI 측 seal 레이블.
- **JSON 필드명은 snake_case 유지** (Python 산출물과 바이트 수준 호환 —
  `@JsonProperty` 또는 SNAKE_CASE 네이밍 전략 적용).

## 5. 표준 스키마 (common/model/NoticeRecord.java)

`Mapper.mapToSchema(raw)`의 출력은 `{"source_file", "records": [...], "images": [...]}`.
레코드는 15개 표준 필드 + `extras`로 구성된다 (Python 버전과 동일).

| 필드 | 의미 |
|---|---|
| `agency` / `notice_no` / `notice_date` / `title` / `signer` | 고시 메타 (기관, 번호, 고시일 ISO, 제목, 고시자) |
| `approval_no` / `approval_date` | 승인·신고·허가 번호/일자 |
| `location` / `area` | 위치·소재지 / 점용·사용 면적 |
| `work_description` / `work_period_start` / `work_period_end` | 공사 내용 / 기간 시작·종료 |
| `applicant_name` / `applicant_address` | 신고자·피허가자 성명(상호) / 주소 |
| `remarks` | 비고 |
| `extras` | 매핑되지 않은 라벨:값 쌍 보존 (동의어 사전 보강용) |

## 6. DB 스키마 (PostgreSQL — resources/db/migration/V1__init.sql)

Python 버전과 동일한 2테이블 구조를 **PostgreSQL 문법**으로 정의한다.
`DbLoader`가 PostgreSQL JDBC 드라이버로 적재한다.

```sql
-- 문서 원본 테이블: 시퀀스 + 표준 필드
CREATE TABLE IF NOT EXISTS documents (
    seq                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- 시퀀스
    source_file        TEXT NOT NULL,        -- 원본 파일명
    file_type          TEXT NOT NULL,        -- hwp / hwpx / hml / pdf
    is_scanned         BOOLEAN NOT NULL DEFAULT FALSE,
    engine             TEXT,                 -- hwplib / owpml / hml-stax / pdfbox / paddleocr-vl
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
```

**SQLite 대비 달라진 점:**

- `INTEGER PRIMARY KEY AUTOINCREMENT` → `BIGINT GENERATED ALWAYS AS IDENTITY`
  (표준 SQL 시퀀스. `SERIAL`도 가능하나 최신 권장은 IDENTITY).
- 날짜 필드는 `TEXT`(ISO 문자열) → 네이티브 `DATE`. 매퍼가 이미 ISO로 정규화하므로
  `LocalDate`로 바인딩하면 된다. 파싱 실패·부분 날짜는 `NULL` 허용.
- `is_scanned`: `INTEGER 0/1` → `BOOLEAN`.
- `extras_json TEXT` → `extras JSONB`. GIN 인덱스로 매핑 안 된 라벨을 직접 질의 가능
  (예: 새 지자체 라벨 빈도 분석).
- `created_at`: 문자열 → `TIMESTAMPTZ DEFAULT now()`.
- 외래키에 `ON DELETE CASCADE` 추가 — documents 재적재(삭제 후 삽입) 시
  연결된 ref_files가 자동 정리된다.

적재 규칙 (Python 버전과 동일):

- **documents 1행 = NoticeRecord 1건.** 한 파일에서 레코드 N건이면 N행,
  `source_file`로 파일 단위 묶임.
- **ref_files**는 이미지가 나온 파일의 첫 레코드 행(대표 행) `seq`에 연결.
  파일 단위 이미지 조회는 `documents.source_file` 조인.
- 삽입 시 생성된 시퀀스 값은 `INSERT ... RETURNING seq`로 받아 ref_files에 연결
  (SQLite의 `last_insert_rowid()` 대체).
- **스키마 마이그레이션은 Flyway**로 관리 (`resources/db/migration/V*.sql`).
  SQLite처럼 런타임에 `ALTER TABLE`로 컬럼을 자동 추가하지 않고, 버전 파일을
  추가하는 방식으로 이력을 남긴다.
- 같은 `source_file` 재적재 시 기존 행 삭제 후 재삽입 (멱등). CASCADE로 ref_files 자동 정리.
- 배치 적재는 **트랜잭션 + `addBatch`/`executeBatch`**로 수행, 파일 단위 실패는
  세이브포인트 롤백 후 다음 파일로 계속 진행 (배치 격리).

## 7. OCR CLI 계약 (PaddleOCR-VL CLI ↔ ScanOcrRunner)

Java가 직접 구동할 수 없는 PaddleOCR-VL만 Python CLI 스크립트로 분리한다.
기존 `scan/paddleocr/reader.py`에 argparse 진입점만 씌우면 되므로 추론 로직
재작성은 없다. 상주 서버 없이 **파일 처리 시마다 서브프로세스로 실행**한다.

```
<ocr.cli.command> <ocr.cli.script> \
    --source-file <원본파일명> \
    --file-type <pdf|hwp|hwpx|hml> \
    --output <raw.json 출력 경로> \
    <입력 파일...>

입력 파일 — 둘 중 하나:
  · 스캔 PDF 원본 1개 (스크립트가 PyMuPDF로 페이지 렌더링)
  · 스캔 HWP/HWPX/HML에서 Java Extractor가 추출한 임베디드 이미지 경로 N개

종료 코드 0 → --output 경로에 §4 raw JSON 계약 그대로 생성
              (도장은 seal 레이블로 제외된 상태. is_scanned는 Java가 true로
               강제하고, markdown 등 계약 외 필드는 무시)
종료 코드 ≠0 → stdout/stderr 로그 꼬리를 오류 메시지로 노출, 해당 파일만 [실패]
```

역할 분담: **임베디드 이미지 추출은 Java가 담당**한다 (HWP/HWPX/HML 파서가 Java에
있으므로). 스크립트는 "이미지/PDF → 구조화 텍스트" 추론만 맡는 얇은 계층으로 유지해
Python 의존성을 최소화한다.

`ScanOcrRunner` 동작 규약:

- `ProcessBuilder` 사용. stdout/stderr는 임시 로그 파일로 리다이렉트하고(파이프
  블로킹 방지), 실패 시 로그 꼬리만 오류 메시지에 싣는다.
- 실행 커맨드·스크립트 경로·타임아웃은 `application.properties`(`ocr.cli.command`,
  `ocr.cli.script`, `ocr.cli.timeout-sec`)에서만 읽는다.
- 스크립트 부재·비정상 종료·타임아웃 시 해당 파일만 `[실패]` 로그 후 다음 파일로
  계속 (배치 격리 원칙 유지). 타임아웃 시 프로세스를 강제 종료한다.
- VLM 추론은 오래 걸릴 수 있으므로 타임아웃은 파일당 수 분 단위로 넉넉히.
  프로세스 실행마다 모델을 다시 로드하는 비용이 있으므로, 스캔본이 많아지면
  스크립트 측에서 모델 캐시 등으로 완화한다.

준비 (스캔본을 처리할 때만 필요):

```bash
cd ocr-cli && pip install -r requirements.txt
python3 ocr-cli/paddleocr_vl_cli.py --source-file x.pdf --file-type pdf --output out.json x.pdf
# 첫 실행 시 VLM 모델 수 GB 다운로드
```

## 8. CLI 규약 및 진입점 (picocli)

```
java -jar extract.jar <서브커맨드> [옵션]
```

| 명령 | 역할 |
|---|---|
| `pipeline -i input/ -o out/ [--no-db]` | 배치: 판별→추출→매핑→적재 일괄 (기존 pipeline.mjs 대체) |
| `detect 파일... [--json]` | 스캔 여부 분류 결과 출력 |
| `extract 파일... -o out/ [--raw] [--no-images] [--engine hwplib]` | 추출+매핑 (엔진 강제 지정 가능) |
| `map raw.json -o out/` | 매핑 전용 (raw JSON → 스키마 JSON) |
| `load out/*.schema.json` | DB 적재 전용 (재적재·스키마 변경 시 단독 실행) |

- 공통 옵션 의미는 Python 버전과 동일 (`--raw`: 원시 결과 포함,
  `--no-images`: 이미지 저장 생략).
- **DB 접속·OCR 실행 정보는 `application.properties`에서만 읽는다**(`db.*`,
  `ocr.cli.*`) — 로컬 실행 전제라 CLI 재정의 옵션은 두지 않는다.
  비밀번호는 파일에 평문으로 두지 말고 환경변수(`PGPASSWORD` 등)나 시크릿
  주입을 권장한다.
- 파일마다 `out/<이름>.schema.json` (+`--raw` 시 `<이름>.raw.json`) 생성.
- 파일 단위 실패는 로그만 남기고 다음 파일로 계속 진행 (배치 격리).

`pipeline` 내부 동작:

```
1) input/ 재귀 스캔 → .hwp/.hwpx/.hml/.pdf 파일 목록 수집
2) 스캔본이 있으면 OCR 스크립트(ocr.cli.script) 존재 확인 (없으면 스캔 파일만 [실패] 처리)
3) 파일별: DetectorRegistry 판별
     스캔본  → (HWP/HWPX/HML이면 임베디드 이미지 추출 후) ScanOcrRunner 서브프로세스 → raw JSON
     네이티브 → ExtractorRegistry에서 확장자 매칭 Extractor → raw JSON
4) Mapper.mapToSchema → out/<이름>.schema.json 저장
5) DbLoader로 documents/ref_files 적재
```

## 9. 새 확장자(형식)·엔진 통합 절차

새 형식 `xyz`(또는 기존 형식의 새 엔진)를 추가할 때:

1. **detect 등록**: `XyzScanDetector implements ScanDetector` 구현 후
   `DetectorRegistry`에 확장자 매핑 추가 (스캔 변형이 없으면 항상 `false` 반환 스텁).
2. **Extractor 구현**: `engine/xyz/XyzExtractor implements Extractor` —
   `extractRaw`가 §4 계약(`RawDocument`)을 지키도록 구현. 병합 셀을 복원할 수 있으면
   `cells`에 span을 채우고, 이미지 저장 시 `path`를 반드시 기록 (매직바이트 확장자 판별 권장).
3. **레지스트리 연결**: `ExtractorRegistry`에 등록. CLI·pipeline은 수정 불필요
   (확장자 라우팅이 레지스트리 기반이므로).
4. **동의어 보강**: 새 문서에서 매핑 안 된 라벨이 `extras`에 남으면
   `Synonyms`의 LABEL_SYNONYMS에 추가.
5. **DB는 수정 불필요**: raw JSON 계약만 지키면 `DbLoader`가 그대로 동작.
6. **테스트**: `src/test/resources/fixtures/`에 픽스처 추가, JUnit 회귀 테스트 작성.
   Python 버전 픽스처를 공유해 두 구현의 결과를 교차 검증.
7. **문서화**: README 형식 표에 행 추가.

## 10. 의존성 요약

### Java (build.gradle)

| 구성 요소 | 의존성 | 비고 |
|---|---|---|
| 언어/빌드 | Java 17+, Gradle | record·text block 사용 |
| CLI | `info.picocli:picocli` | 서브커맨드 구조 |
| JSON | `com.fasterxml.jackson.core:jackson-databind` | snake_case 전략으로 Python 산출물과 호환 |
| HWP | `kr.dogfoot:hwplib` | 문단/표/BinData 접근 |
| HWPX | `kr.dogfoot:hwpxlib` | 인라인 태그 셀 잘림 픽스처 검증 필수, 문제 시 자체 StAX 파싱으로 대체 |
| HML | JDK 내장 (StAX, `java.util.Base64`) | 외부 의존성 없음 |
| PDF | `org.apache.pdfbox:pdfbox` | 표 탐지(선분 클러스터링)는 자체 포팅 |
| DB 드라이버 | `org.postgresql:postgresql` | 순수 JDBC (ORM 불필요) |
| 커넥션 풀 | `com.zaxxer:HikariCP` | 배치 적재 성능·안정성 |
| 마이그레이션 | `org.flywaydb:flyway-core` | `resources/db/migration/V*.sql` 버전 관리 |
| 테스트 | JUnit 5, Testcontainers(PostgreSQL) | 픽스처 회귀 + 실제 PG 컨테이너로 적재 검증 |

### Python (PaddleOCR-VL CLI 스크립트, `ocr-cli/`)

| 구성 요소 | 의존성 | 비고 |
|---|---|---|
| OCR 엔진 | `paddleocr[doc-parser]`, `paddlepaddle>=3.2.1` | VLM 모델 수 GB 자동 다운로드, GPU 권장 |
| PDF 렌더링 | `pymupdf` | 스캔 PDF 페이지 → 이미지 |

## 11. Python 버전과의 대응표 (이행 참고)

| Python 버전 | Java 버전 |
|---|---|
| `pipeline.mjs` (Node) | `PipelineCommand` (picocli) |
| `python -m detect` (프로세스 호출) | `DetectorRegistry` (JVM 내 직접 호출) |
| `common/` (mapper, synonyms, heuristics…) | `common/` 패키지 (동일 로직 포팅) |
| `hwp/libhwp`, `hwp/rhwp` | `HwplibExtractor` (hwplib 단일 엔진으로 통합) |
| `hwpx/owpml` (자체 HwpxParser) | `OwpmlExtractor` (hwpxlib, 필요 시 자체 StAX) |
| `hml/stdlib` | `HmlExtractor` (StAX) |
| `pdf/pdfplumber` (+PyMuPDF) | `PdfBoxExtractor` + `TableDetector` (클러스터링 포팅) |
| `scan/paddleocr` (직접 임포트) | PaddleOCR-VL CLI 스크립트 + `ScanOcrRunner` (서브프로세스) |
| `ocr/embedded.py` (pytesseract) | 제거 — 임베디드 이미지 OCR은 사용하지 않음 |
| `db/` (sqlite3) | `DbSchema` + `DbLoader` (PostgreSQL JDBC + HikariCP + Flyway) |
| raw JSON / 스키마 JSON / DB DDL | **완전 동일 (교차 검증 가능)** |
