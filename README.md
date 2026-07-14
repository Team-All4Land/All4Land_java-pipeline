# 공유수면 고시문 추출 파이프라인 (Java)

공유수면 점용·사용 고시류 문서(**HWP / HWPX / HML / PDF — 네이티브·스캔본 모두**)에서
텍스트·표·이미지를 추출하고, 표준 필드 스키마로 정규화한 뒤 **PostgreSQL DB에
적재**하는 배치 파이프라인입니다.

핵심 원칙: **판별(detect) → 추출(engine) → 매핑(common) → 적재(db)** 4계층 분리.
PaddleOCR-VL은 Java에서 직접 구동할 수 없으므로 스캔본 처리만 별도의 Python
API 서비스(`ocr-service/`, 이 저장소 범위 밖)로 분리하고, Java 파이프라인이
HTTP로 호출합니다.

## 전체 흐름

```
입력 파일 (.hwp / .hwpx / .hml / .pdf)   ← 모든 확장자가 스캔본일 수 있음
        │
        ▼
CLI: extract.jar pipeline (picocli)
        │
        │  [1차 분기] 스캔본 판별 — DetectorRegistry
        │
        ├── 스캔본 ──► ScanOcrClient ──HTTP──► ocr-service (별도 Python, 범위 밖)
        │
        └── 네이티브 ── [2차 분기] 확장자별 Extractor
                ├─ .hwp   →  HwplibExtractor  (hwplib)
                ├─ .hwpx  →  OwpmlExtractor   (hwpxlib)
                ├─ .hml   →  HmlExtractor     (JDK DOM, 외부 의존성 없음)
                └─ .pdf   →  PdfBoxExtractor  (Apache PDFBox + 선분 클러스터링 표 탐지)
        │
        ▼
raw JSON (공통 계약 — snake_case, Jackson DTO)
        ▼
Mapper.mapToSchema (라벨 정규화 + 동의어 사전 매핑 + 값 정규화)
        ▼
표준 스키마 JSON {"source_file", "records": [...], "images": [...]}
        ▼
DbLoader (PostgreSQL JDBC + HikariCP)
        ▼
PostgreSQL (documents / ref_files 2테이블) + images/ 폴더
```

## 지원 형식

| 형식 | 판별기 | 추출 엔진 | 비고 |
|---|---|---|---|
| `.hwp` | `HwpScanDetector` | `HwplibExtractor` (hwplib) | 이미지 위치 복원 불가(BinData 전체 추출) |
| `.hwpx` | `HwpxScanDetector` | `OwpmlExtractor` (hwpxlib) | 인라인 태그(`<hp:fwSpace/>` 등) 텍스트 잘림 방지 처리 |
| `.hml` | `HmlScanDetector` | `HmlExtractor` (JDK DOM) | ColSpan/RowSpan 병합 구조 복원 |
| `.pdf` | `PdfScanDetector` | `PdfBoxExtractor` | 선분 클러스터링 표 탐지, 도장 제외 휴리스틱 |

스캔 판별 기준: 네이티브 본문 텍스트량이 거의 없으면서(형식별 임계치 미만)
임베디드 이미지가 있으면 스캔본으로 판정합니다.

## 디렉터리 구조

```
src/main/java/com/onnara/extract/
├── cli/          picocli 진입점 — Main + 5개 서브커맨드
├── detect/       1차 분기: 스캔본 판별 (ScanDetector 구현체 4종)
├── engine/       2차 분기: 확장자별 네이티브 추출기 (Extractor 구현체 4종)
├── common/       형식 공통 계층 — raw JSON 모델, 매퍼, 동의어 사전, 날짜/주소 휴리스틱
├── ocr/          임베디드 이미지 OCR (Tess4J, --ocr 옵션)
├── scan/         스캔본 처리 — Python OCR 서비스 HTTP 클라이언트
└── db/           PostgreSQL 적재 (HikariCP + Flyway)

src/main/resources/
├── application.properties   DB·OCR 서비스 접속 설정
└── db/migration/            Flyway 마이그레이션 (V1__init.sql)

src/test/java/...   JUnit 5 — detect/engine/common/scan 단위 테스트 + samples/ 기반 회귀 테스트
samples/             실제 고시문 픽스처 (형식·스캔 여부별)
```

## 요구 사항

- JDK 17 이상
- Maven 3.6 이상
- (DB 적재 시) PostgreSQL 접근 가능한 인스턴스
- (`--ocr` 사용 시) 네이티브 Tesseract + 한국어 데이터(`kor.traineddata`) 설치,
  `TESSDATA_PREFIX` 환경변수. 미설치 환경에서는 `--ocr`를 켜도 배치가 실패하지
  않고 경고만 남깁니다.
- 한글 파일명 경로를 다루므로 로캘이 UTF-8이어야 합니다(`LANG=C.UTF-8` 등).
  `mvn test`는 surefire 설정에 이미 반영되어 있어 별도 조치가 필요 없습니다.

## 빌드

```bash
mvn package
```

`target/extract-pipeline-1.0.0.jar`(fat-jar)가 생성됩니다.

## CLI 사용법

```
java -jar target/extract-pipeline-1.0.0.jar <서브커맨드> [옵션]
```

| 명령 | 역할 |
|---|---|
| `pipeline -i input/ -o out/ [--db-url ...] [--ocr-url ...] [--no-db]` | 배치: 판별→추출→매핑→적재 일괄 |
| `detect 파일...\|폴더 [--json]` | 스캔 여부 분류 결과 출력 |
| `extract 파일...\|폴더 -o out/ [--raw] [--no-images] [--ocr] [--engine hwplib]` | 추출+매핑 (DB 적재 없음, 엔진 강제 지정 가능) |
| `map raw.json... -o out/` | 매핑 전용 (raw JSON → 스키마 JSON) |
| `load schema.json... [--db-url ...]` | DB 적재 전용 (재적재·스키마 변경 시 단독 실행) |

공통 옵션: `--raw`(원시 결과도 저장), `--no-images`(이미지 저장 생략),
`--ocr`(임베디드 이미지 Tesseract OCR). 파일 단위 실패는 `[실패] <파일>: <사유>`
로그만 남기고 배치는 계속 진행합니다(배치 격리).

### 예시

```bash
# DB 없이 추출+매핑만 (스모크 테스트)
java -jar target/extract-pipeline-1.0.0.jar pipeline -i samples -o out/ --no-db

# 스캔 여부만 확인
java -jar target/extract-pipeline-1.0.0.jar detect samples --json

# PostgreSQL까지 적재
java -jar target/extract-pipeline-1.0.0.jar pipeline -i input/ -o out/ \
    --db-url jdbc:postgresql://localhost:5432/extract --db-user extract
```

## 설정 (`application.properties`)

```properties
db.url=jdbc:postgresql://localhost:5432/extract
db.user=extract
db.password=
db.pool.max-size=5

ocr.service.url=http://127.0.0.1:8000
ocr.service.timeout-sec=300
ocr.service.retries=2
```

- DB 비밀번호는 파일에 평문으로 두지 말고 환경변수 `PGPASSWORD` 또는
  `DB_PASSWORD`로 주입하세요. CLI `--db-password` > 환경변수 > 파일 순으로
  우선합니다.
- 모든 값은 CLI 옵션(`--db-url`, `--ocr-url` 등)으로 재정의할 수 있습니다.

## 스캔본 OCR 서비스 (ocr-service, 범위 밖)

PaddleOCR-VL을 구동하는 Python FastAPI 서비스는 이 저장소에 포함되지 않습니다.
`scan/ScanOcrClient`가 기대하는 계약은 다음과 같습니다.

```
GET  /health
  → 200 {"status": "ok", "model_loaded": true}

POST /v1/parse   (multipart/form-data)
  요청 — 둘 중 하나:
    file      : 스캔 PDF 원본
    images[]  : 스캔 HWP/HWPX/HML에서 Java가 추출한 임베디드 이미지들
  공통 메타: source_file, file_type
  응답: 200 → raw JSON 계약(§ 위 다이어그램) + is_scanned=true (+markdown 필드는 무시)
        422/500 → {"error": "..."}
```

서비스가 없거나 응답하지 않으면 스캔본 파일만 `[실패]` 처리되고, 네이티브
파일들은 정상적으로 배치가 진행됩니다.

## 데이터베이스 스키마 (PostgreSQL)

Flyway 마이그레이션(`src/main/resources/db/migration/V1__init.sql`)이 2테이블을
생성합니다.

| 테이블 | 설명 |
|---|---|
| `documents` | 문서 1레코드 = 1행. 15개 표준 필드(`agency`, `notice_no`, `notice_date`, `title`, `approval_no`, `location`, `area`, `work_period_start/end` 등) + `engine`/`is_scanned` + 매핑 안 된 라벨을 보존하는 `extras JSONB` |
| `ref_files` | 이미지 1개 = 1행. `documents.seq`를 참조하며 `ON DELETE CASCADE` |

적재 규칙: 같은 `source_file` 재적재 시 기존 행을 삭제 후 재삽입(멱등,
CASCADE로 `ref_files` 자동 정리). 파일 단위로 세이브포인트를 잡아 한 파일의
실패가 배치 전체를 막지 않습니다.

## 테스트

```bash
mvn test                # 단위 + samples/ 기반 회귀 테스트 (DB 불필요)

# 실제 PostgreSQL이 있을 때만: DbLoader 통합 테스트
mvn test -Dgroups=db -DexcludedGroups= \
    -Ddb.test.url=jdbc:postgresql://localhost:5432/extract \
    -Ddb.test.user=extract -Ddb.test.password=extract
```

`ScanOcrClient`는 JDK 내장 `HttpServer`로 목 서버를 띄워 검증하므로 외부
서비스 없이도 실행됩니다.

## 새 형식(확장자)·엔진 통합 절차

1. **detect 등록**: `XyzScanDetector implements ScanDetector` 구현 후
   `DetectorRegistry`에 확장자 매핑 추가 (스캔 변형이 없으면 항상 `false` 스텁).
2. **Extractor 구현**: `engine/xyz/XyzExtractor implements Extractor` —
   `extractRaw`가 raw JSON 계약을 지키도록 구현. 병합 셀은 `cells`에 span을
   채우고, 이미지 저장 시 `path`를 기록(`ImageFormats.extensionFor`로 매직바이트
   확장자 판별 권장). `saveImages`는 `extractRaw`의 이미지와 순서·이름이
   동일해야 한다.
3. **레지스트리 연결**: `ExtractorRegistry`에 등록. CLI는 수정 불필요.
4. **동의어 보강**: 새 문서에서 매핑 안 된 라벨이 `extras`에 남으면
   `common/Synonyms`의 `LABEL_SYNONYMS`에 추가.
5. **DB는 수정 불필요**: raw JSON 계약만 지키면 `DbLoader`가 그대로 동작.
6. **테스트**: `src/test/java/.../engine/xyz/`에 `samples/` 기반 회귀 테스트 추가.
