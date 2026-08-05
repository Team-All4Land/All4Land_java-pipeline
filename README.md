# 공유수면 고시문 추출 파이프라인 (Java)

공유수면 점용·사용 고시류 문서(**HWP / HWPX / HML / PDF — 네이티브·스캔본 모두**)에서
텍스트·표·이미지를 추출하고, 표준 필드 스키마로 정규화한 뒤 **PostgreSQL DB에
적재**하는 배치 파이프라인입니다.

핵심 원칙: **판별(detect) → 추출(engine) → 매핑(common) → 적재(db)** 4계층 분리.
PaddleOCR-VL은 Java에서 직접 구동할 수 없으므로 스캔본 처리만 Python CLI
스크립트(`ocr-cli/`)에 맡기고, Java 파이프라인이 **서브프로세스로 실행**합니다.

## 전체 흐름

```
입력 파일 (.hwp / .hwpx / .hml / .pdf)   ← 모든 확장자가 스캔본일 수 있음
        │
        ▼
CLI: extract.jar pipeline (picocli)
        │
        │  [1차 분기] 스캔본 판별 — DetectorRegistry
        │
        ├── 스캔본 ──► ScanOcrRunner ──서브프로세스──► PaddleOCR-VL CLI (ocr-cli/, Python)
        │
        └── 네이티브 ── [2차 분기] 확장자별 Extractor
                ├─ .hwp   →  HwplibExtractor  (hwplib)
                ├─ .hwpx  →  OwpmlExtractor   (hwpxlib)
                ├─ .hml   →  HmlExtractor     (JDK DOM, 외부 의존성 없음)
                └─ .pdf   →  PdfBoxExtractor  (Apache PDFBox + 선분 클러스터링 표 탐지)
                │
                └── + 임베디드 이미지는 파일로 저장하고 images 메타만 기록 (OCR 안 함)
        │
        ▼
raw JSON (공통 계약 — snake_case, Jackson DTO)
        ▼
TableInterpreter (표 서식 판정 + 라벨:값 추출 + 사전 매핑 판정)
        ▼
표 해석 JSON *.tables.json   ← 진단용 중간 산출물 (`--tables` 지정 시 저장)
        ▼
Mapper.mapToSchema (문단 메타·라벨 + 표 해석 결과 적용 + 값 정규화)
        ▼
LoadPolicy (본문 분량 측정 → 안내문류면 db_skip_reason 기록, 적재만 제외)
        ▼
표준 스키마 JSON {"source_file", "body_chars", "records": [...], "images": [...]}
        ▼
DbLoader (PostgreSQL JDBC + HikariCP)
        ▼
PostgreSQL (documents / ref_files 2테이블) + images/ 폴더
```

## 지원 형식

| 형식 | 판별기 | 추출 엔진 | 비고 |
|---|---|---|---|
| `.hwp` | `HwpScanDetector` | `HwplibExtractor` (hwplib) | 이미지 위치 복원 불가(BinData 전체 추출), 확장자는 스트림명 폴백 |
| `.hwpx` | `HwpxScanDetector` | `OwpmlExtractor` (hwpxlib) | 인라인 태그(`<hp:fwSpace/>` 등) 텍스트 잘림 방지 처리 |
| `.hml` | `HmlScanDetector` | `HmlExtractor` (JDK DOM) | ColSpan/RowSpan 병합 구조 복원 |
| `.pdf` | `PdfScanDetector` | `PdfBoxExtractor` | 선분 클러스터링 표 탐지, 도장 제외 휴리스틱 |

스캔 판별 기준(네 형식 공통):

```
스캔본 = 네이티브 본문 텍스트가 한 글자도 없다 AND 임베디드 이미지가 1개 이상
```

**이미지의 개수·크기·면적은 판정 근거가 아닙니다.** 사진이 지면 대부분을 차지한다는 것만으로는
스캔본이 아닙니다 — 붙임 현장사진·위치도처럼 본문이 사진으로 채워진 네이티브 문서가 흔합니다.
지면 점유율도 신호가 되지 않습니다: 본문 전체가 이미지 한 장인 문서와 붙임 사진이 여러 장
들어간 문서를 실측하면 둘 다 **본문 폭의 96~100%, 인쇄 영역의 약 40%**로 구분되지 않습니다.

임계치가 한 글자라 판별의 부담은 전부 "본문 텍스트를 빠짐없이, 그리고 본문만 세는가"에
있습니다. 표 셀·중첩 표·글상자·캡션까지 세되, 머리말·꼬리말·각주는 세지 않습니다
(진짜 스캔본에도 머리글·쪽번호는 남아 있어, 세면 반대 방향 오판이 납니다).

PDF도 같은 규칙을 쓰지만 **첫 페이지에만** 적용합니다 — 첫 페이지에서 텍스트가 한 글자라도
뽑히면 네이티브고, 한 글자도 없으면서 이미지가 있으면 스캔본입니다. 다만 PDFBox는 페이지
텍스트를 평면화해서 머리말·꼬리말을 구조적으로 제외할 수 없어, 스캔 페이지에 쪽번호나
전자문서 스탬프 텍스트가 남아 있으면 네이티브로 판정됩니다(그 경우 페이지마다 "텍스트가 없는
페이지입니다" 경고가 남습니다).

## 디렉터리 구조

```
src/main/java/com/onnara/extract/
├── cli/          picocli 진입점 — Main + 7개 서브커맨드
├── detect/       1차 분기: 스캔본 판별 (ScanDetector 구현체 4종)
├── engine/       2차 분기: 확장자별 네이티브 추출기 (Extractor 구현체 4종)
├── common/       형식 공통 계층 — raw JSON 모델, 매퍼, 동의어 사전, 날짜/주소 휴리스틱
├── scan/         스캔본 처리 — PaddleOCR-VL CLI 서브프로세스 실행기
└── db/           PostgreSQL 적재 (HikariCP + Flyway)

src/main/resources/
├── application.properties   DB 접속·OCR CLI 실행 설정 (설정의 단일 출처)
└── db/migration/            Flyway 마이그레이션 (V1__init.sql)

src/test/java/...   JUnit 5 — detect/engine/common/scan 단위 테스트 + samples/ 기반 회귀 테스트
samples/             실제 고시문 픽스처 (형식·스캔 여부별)
ocr-cli/             스캔본 OCR용 PaddleOCR-VL Python CLI (ScanOcrRunner가 서브프로세스로 호출)
```

`common/`에는 계층을 가로지르는 공용 유틸도 있습니다: `Errors`(예외 → 원인 체인 사유 문장 —
모든 `[실패]` 로그의 출처), `ImageFormats`(저장 확장자 판별), `DocumentSize`·`LoadPolicy`
(적재 여부 판정), `table/TableRenderer`(표 → HTML 복원). `detect/`에는 실패 갈래 분류
`FailureKind`·`FailureClassifier`가 있습니다.

## 요구 사항

- JDK 17 이상
- Maven 3.6 이상
- (DB 적재 시) PostgreSQL 접근 가능한 인스턴스
- (스캔본 처리 시) Python 3 + `ocr-cli/`의 PaddleOCR-VL 스크립트(설치는 `ocr-cli/requirements.txt`).
  없으면 스캔본 파일만 `[실패]` 처리되고 네이티브 파일은 정상 처리됩니다.
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
| `pipeline -i input/ -o out/ [--no-db] [--raw] [--tables]` | 배치: 판별→추출→표해석→매핑→적재 일괄 |
| `detect 파일...\|폴더 [--json] [--summary] [--failures f.json]` | 스캔 여부 분류·집계 (추출·OCR 없음) |
| `extract 파일...\|폴더 -o out/ [--raw] [--no-images] [--engine hwplib]` | 추출+매핑 (DB 적재 없음, 엔진 강제 지정 가능) |
| `tables raw.json... -o out/ [--summary]` | 표 해석 전용 (raw JSON → 표 해석 JSON) |
| `render raw.json...\|폴더 -o out/` | **표 복원 전용 (raw JSON → HTML, 검수용)** |
| `map raw.json... -o out/` | 매핑 전용 (raw JSON → 스키마 JSON) |
| `load schema.json...` | DB 적재 전용 (재적재·스키마 변경 시 단독 실행) |
| `dict [-o docs/SYNONYMS.md] [--review out/]` | 동의어 사전·미매핑 라벨 검토 문서 생성 |

공통 옵션: `--raw`(원시 결과도 저장), `--tables`(표 해석 중간 결과도 저장),
`--no-images`(이미지 저장 생략), `--failures <경로>`(실패 건만 JSON으로 저장),
`--stacktrace`(실패 시 스택 트레이스도 출력).
DB 접속·OCR 실행 정보는 CLI 옵션이 아니라 `application.properties` 및
`.env`에서 읽습니다(우선순위: OS 환경변수 > `.env` > `application.properties`).
파일 단위 실패는 `[실패] <파일>: (<단계>) <사유>` 로그만 남기고 배치는 계속
진행합니다(배치 격리). `ref_files.file_path`에는 저장된 이미지의 **절대경로**가
기록됩니다(리눅스 서버에서 파일을 절대경로로 참조).

### 예시

```bash
# DB 없이 추출+매핑만 (스모크 테스트)
java -jar target/extract-pipeline-1.0.0.jar pipeline -i samples -o out/ --no-db

# 스캔 여부만 확인
java -jar target/extract-pipeline-1.0.0.jar detect samples --json

# 전체 문서 중 스캔본이 몇 개인지만 집계 (파일별 목록 없이)
java -jar target/extract-pipeline-1.0.0.jar detect input/ --summary

# PostgreSQL까지 적재 (접속 정보는 application.properties에서)
java -jar target/extract-pipeline-1.0.0.jar pipeline -i input/ -o out/

# 매핑 진단: 표를 어떻게 읽었는지 중간 산출물로 확인
java -jar target/extract-pipeline-1.0.0.jar pipeline -i samples -o out/ --no-db --tables

# 동의어 사전 문서 + 미매핑 라벨 검토 리포트 생성
java -jar target/extract-pipeline-1.0.0.jar dict --review out/

# 판별 실패 사유를 갈래별로 보고, 전건 목록을 파일로 남기기
java -jar target/extract-pipeline-1.0.0.jar detect input/ --summary --failures out/detect-failures.json

# 추출한 표를 원본 병합 그대로 HTML로 복원 (검수용)
java -jar target/extract-pipeline-1.0.0.jar pipeline -i input/ -o out/ --no-db --raw
java -jar target/extract-pipeline-1.0.0.jar render out/ -o out/
```

## 매핑 정교화 — 표 해석과 동의어 사전

고시문은 정보 대부분이 표에 있고, 기관마다 서식과 라벨이 다릅니다
(`고시일자` / `공고일자` …). 이를 두 단계로 나눠 다룹니다.

**1. 표 해석 중간 단계 (`*.tables.json`)** — 표준 스키마는 "어느 필드가 무슨 값이
됐는가"만 남기므로, 값이 비었을 때 표를 잘못 읽은 것인지 사전에 라벨이 없는 것인지
구분되지 않습니다. 중간 단계는 표별 서식 판정(`kind`), 병합 셀을 정확히 읽었는지
(`span_aware`), 각 라벨의 **원본 격자 좌표**와 사전 매핑 여부를 그대로 남깁니다.

**2. 동의어 사전 (`src/main/resources/synonyms.json`)** — 사전 본문은 이 파일 하나이며,
필드 설명·예시·검토 메모를 함께 담습니다. 동의어는 사람이 읽는 형태(`점용·사용의 장소`)로
적으면 되고 로드할 때 정규화됩니다. 같은 라벨이 두 필드에 중복 등재되면 **기동 시
오류로 중단**되어 매핑 충돌이 배포 전에 드러납니다.

검토 문서는 `dict` 서브커맨드로 생성합니다(손으로 고치지 않습니다).

| 문서 | 내용 |
|---|---|
| `docs/SYNONYMS.md` | 필드별 설명·값 예시·인식하는 라벨 전체 목록, 정규화 규칙, 보강 절차 |
| `docs/EXTRAS_REVIEW.md` | 미매핑 라벨 빈도순 집계(예시 값·출현 문서), 표준 필드 채움률, 판단 기준 |

사전 보강은 **표본이 아니라 전량 집계**로 판단합니다 — 라벨 표기 습관이 기관 단위로
몰려 다니기 때문에, 일부만 표본으로 보면 특정 기관의 표기가 통째로 누락됩니다.
라벨을 추가한 뒤 파이프라인을 다시 돌리면 파일 단위 멱등 적재라 기존 문서도
갱신되어, `extras`에 있던 값이 표준 컬럼으로 승격됩니다.

## 설정 (`application.properties` + `.env`)

DB 접속과 OCR 실행 정보는 아래 세 곳에서 읽으며, 우선순위는
**OS 환경변수 > `.env` > `application.properties`**입니다. 기본값은
`application.properties`에 두고, 서버마다 다른 값(접속 정보·비밀번호 등)은
`.env`로 덮어씁니다.

```properties
# application.properties (빌드에 포함되는 기본값)
db.url=jdbc:postgresql://localhost:5432/extract
db.user=extract
db.password=
db.pool.max-size=5

ocr.cli.command=python3
ocr.cli.script=ocr-cli/paddleocr_vl_cli.py
ocr.cli.timeout-sec=300

# 본문이 이 글자 수를 넘으면 안내문류로 보고 DB 적재만 건너뜀 (0이면 제한 없음)
map.max-body-chars=5000
```

### 리눅스 서버 배포 — `.env`로 환경변수 관리

리눅스 서버에서는 접속 정보를 빌드 산출물이 아닌 `.env` 파일로 관리합니다.
`.env.example`을 복사해 값을 채우세요(`.env`는 커밋 대상이 아닙니다).

```bash
cp .env.example .env   # 이후 값 편집
```

```dotenv
# .env — 실행 디렉터리에서 자동 로드(다른 위치면 APP_ENV_FILE로 경로 지정)
DB_URL=jdbc:postgresql://localhost:5432/extract
DB_USER=extract
DB_PASSWORD=            # 또는 OS 환경변수 PGPASSWORD / DB_PASSWORD
DB_POOL_MAX_SIZE=5

OCR_CLI_COMMAND=python3            # venv 사용 시 해당 python 절대경로
OCR_CLI_SCRIPT=ocr-cli/paddleocr_vl_cli.py
OCR_CLI_TIMEOUT_SEC=300

MAP_MAX_BODY_CHARS=5000            # 안내문류 적재 제외 임계치 (0이면 제한 없음)
```

- DB 비밀번호는 파일(`application.properties`)에 평문으로 두지 말고
  `.env` 또는 OS 환경변수 `PGPASSWORD`/`DB_PASSWORD`로 주입하세요.
- `OCR_CLI_COMMAND`는 가상환경을 쓰면 해당 venv의 `python` 절대경로로
  지정하세요.

## 스캔본 OCR — PaddleOCR-VL CLI (`ocr-cli/`)

PaddleOCR-VL을 구동하는 Python 스크립트(`ocr-cli/paddleocr_vl_cli.py`)가 저장소에
포함돼 있습니다. `scan/ScanOcrRunner`가 아래 계약으로 **서브프로세스를 실행**합니다.
설치·상세는 [`ocr-cli/README.md`](ocr-cli/README.md) 참고.

```
<ocr.cli.command> <ocr.cli.script> \
    --source-file <원본파일명> \
    --file-type <pdf|hwp|hwpx|hml> \
    --output <raw.json 출력 경로> \
    <입력 파일...>

입력 파일 — 둘 중 하나:
  · 스캔 PDF 원본 1개 (스크립트가 페이지 렌더링 후 VLM 추론)
  · 스캔 HWP/HWPX/HML에서 Java가 추출한 임베디드 이미지 경로 N개

종료 코드 0 → --output 경로에 raw JSON 계약(위 다이어그램) 파일 생성.
              is_scanned는 Java가 true로 강제하고, 계약 외 필드는 무시합니다.
              stdout/stderr는 로그로만 취급합니다.
종료 코드 ≠0 또는 제한 시간 초과 → 해당 파일만 [실패] 처리.
```

### 네이티브 문서에 삽입된 이미지는 OCR하지 않습니다

**OCR은 스캔 판정을 받은 파일만 탑니다.** 네이티브 문서(본문 텍스트가 있는 문서)에 붙임
현장사진·위치도가 들어 있어도 그 이미지는 파일로 저장하고 `images` 메타만 기록하며, 사진
속 글자를 본문에 섞지 않습니다.

따라서 네이티브 문서를 처리할 때 Python 서브프로세스는 실행되지 않습니다 — 배치 시간이
문서의 삽입 이미지 개수에 좌우되지 않고, `is_scanned=false`인 문서의 `content`에는 항상
네이티브로 읽어 낸 문단·표만 담깁니다.

`paddleocr` 미설치 등으로 스크립트를 실행할 수 없으면(경로 확인: `ocr.cli.script`)
스캔본 파일만 `[실패]` 처리되고, 네이티브 파일 처리에는 영향이 없습니다.

### OCR 전에 스캔본이 몇 건인지 먼저 세기

`detect --summary`는 판별 단계만 돌립니다 — 추출·매핑·DB 적재는 물론 OCR 서브프로세스도
띄우지 않으므로 전체 문서에 돌려도 비용은 파일 파싱뿐입니다. 스캔본 건수를 먼저 알면
배치 소요 시간과 `ocr.cli.timeout-sec`(파일당 제한)를 가늠할 수 있습니다.

```bash
java -jar target/extract-pipeline-1.0.0.jar detect input/ --summary
```

```
[집계] 총 8개 — 스캔본 1개(12.5%), 네이티브 7개(87.5%), 판별 실패 0개
       hml   총 3개 · 스캔본 0 · 네이티브 3 · 실패 0
       hwp   총 2개 · 스캔본 0 · 네이티브 2 · 실패 0
       hwpx  총 3개 · 스캔본 1 · 네이티브 2 · 실패 0
```

판별 실패(지원하지 않는 확장자, 손상 파일)는 네이티브가 아니라 별도로 세고 비율의
분모에서도 빠집니다 — 실패를 네이티브에 합치면 스캔본 비중이 실제보다 낮게 보입니다.
`--json`을 함께 주면 `summary` / `by_extension` / `by_failure_kind` /
`files`(`--summary`면 생략) 구조로 출력됩니다.

## 실패 사유 읽기

### 판별 실패

전량 배치에서 나오는 수백 건의 실패는 "판별 실패 203개"라는 총계만으로는 손쓸 수 없습니다.
**변환하면 살릴 수 있는 것**(한글 3.0 구버전), **원본을 다시 받아야 하는 것**(암호 문서),
**포기할 것**(손상 파일)은 대응이 전혀 다릅니다. 그래서 갈래별로 나눠 셉니다
(`--summary`에서도 나옵니다).

```
[실패 사유] 총 203개
       HWP3_LEGACY         141개  한글 3.0 이하 구버전 파일 — hwplib이 읽지 못합니다(한글에서 재저장 필요)
                          예) input/2015_고시_001.hwp
       ENCRYPTED            38개  암호가 걸려 있습니다(배포용 문서 포함) — 암호 해제본이 필요합니다
       NOT_ZIP              16개  HWPX 컨테이너(ZIP)가 아닙니다 — .hwp를 .hwpx로 개명했을 수 있습니다
       …
```

| 갈래 | 뜻 | 대응 |
|---|---|---|
| `HWP3_LEGACY` | 한글 3.0 이하 — CFB가 아니라 hwplib이 못 읽음 | 한글에서 재저장(변환)하면 살아남 |
| `NOT_COMPOUND_FILE` | `.hwp`인데 OLE 복합문서가 아님 | 실제 형식 확인 후 확장자 교정 |
| `NOT_ZIP` | `.hwpx`인데 ZIP이 아님(대개 `.hwp` 개명) | 확장자 교정 |
| `ENCRYPTED` | 암호·배포용 문서 | 암호 해제본 확보 |
| `ZIP_CORRUPT` / `XML_PARSE` | 컨테이너·본문 XML 손상 | 원본 재수집 |
| `DOCUMENT_PARSE` | 컨테이너는 정상, 내부 구조에서 깨짐 | 개별 확인(라이브러리 미지원 레코드일 수 있음) |
| `PDF_LOAD` | PDF를 열지 못함 | `%PDF` 서명 유무를 상세에서 확인 |
| `EMPTY_FILE` / `FILE_ACCESS` | 0바이트 / 경로·권한 문제 | 수집 단계 점검 |
| `OUT_OF_MEMORY` | 파일 하나가 힙을 다 씀 | `-Xmx` 상향 또는 해당 파일 제외 |

갈래는 **파일 앞부분의 매직바이트**와 **원인 체인의 맨 끝 예외** 두 가지로 판정합니다.
라이브러리는 "읽지 못했다"까지만 말해 주지만, 헤더를 직접 보면 "한글 3.0이라 애초에 대상이
아니다"와 "정상 컨테이너인데 안이 깨졌다"가 갈립니다.

전건 목록은 `--failures`로 저장합니다 — 재처리 대상을 그대로 넘길 수 있습니다.

```bash
java -jar target/extract-pipeline-1.0.0.jar detect input/ --summary --failures out/detect-failures.json
```

```json
{"total": 23702, "failed": 203, "failures": [
  {"file": "input/2015_고시_001.hwp", "ext": "hwp", "kind": "HWP3_LEGACY",
   "description": "한글 3.0 이하 구버전 파일 — hwplib이 읽지 못합니다(한글에서 재저장 필요)",
   "error": "UncheckedIOException: HWP 스캔 판별 실패: … [원인: IOException: Unable to read entire header; 28 bytes read; expected 512 bytes]"}
]}
```

판별 중 한 파일에서 `OutOfMemoryError`가 나도 그 건만 `OUT_OF_MEMORY`로 세고 배치는
계속됩니다 — 수만 건 배치가 파일 하나 때문에 통째로 멈추지 않습니다.

### 추출 실패

추출 단계도 같은 규약을 씁니다. 로그에 **어느 단계에서 깨졌는지**와 **원인 체인**이 함께
남습니다(예전에는 맨 바깥 예외의 메시지만 찍혀 "읽지 못했다"는 사실만 남았습니다).

```
[실패] input/개명파일.hwpx: (판별) UncheckedIOException: HWPX 스캔 판별 실패: … [원인: ZipException: zip END header not found] (실제 내용은 HWP 5.0 컨테이너입니다)
[실패] input/스캔본.hwpx: (추출) ScanOcrException: OCR 프로세스 실패(종료 코드 1): paddleocr(PaddleOCR-VL)가 설치되어 있지 않습니다. …
```

단계는 `판별` / `추출` / `표해석` / `매핑` / `저장` 다섯입니다. 실패한 파일은 `schema.json`이
아예 생기지 않으므로, 사후 대조가 필요하면 `--failures`로 목록을 남기세요.

```bash
java -jar target/extract-pipeline-1.0.0.jar pipeline -i input/ -o out/ \
     --failures out/extract-failures.json --stacktrace
```

## 긴 문서는 적재에서 뺀다 (안내문류 차단)

입력 폴더에는 고시문만 들어오지 않습니다. 전자문서 이용안내·업무편람 같은 문서가 섞여 오는데,
여기에는 기관·고시번호·점용 장소 같은 고시 항목이 없습니다. 그대로 적재하면 표준 컬럼이 전부 빈
행이 쌓이고, 본문에서 주워 담은 라벨이 `extras`로 흘러들어 사전 보강 통계를 오염시킵니다.

**추출과 매핑은 정상적으로 끝냅니다.** 본문이 `map.max-body-chars`를 넘으면 매핑 단계에서
사유를 기록하고, **DB 적재만** 건너뜁니다. `raw.json`과 `schema.json`은 그대로 남으므로
판단이 틀렸으면 `load`로 다시 넣으면 됩니다.

```
[적재제외] 전자문서 이용안내.pdf: 본문 8,355자 (임계 5,000자 초과) — 안내문류로 보고 DB 적재를 건너뜁니다
DB 적재: 7개 파일, documents 9행, ref_files 3행 (적재제외 1개)
```

판정은 `SchemaResult`에 남으므로 `pipeline` 한 번에 돌리든 `map` → `load`로 나눠 돌리든
같은 파일이 같은 결정을 받습니다.

```json
{"source_file": "전자문서 이용안내.pdf", "body_chars": 8355,
 "db_skip_reason": "본문 8,355자 (임계 5,000자 초과)", "records": [...]}
```

### 적재 임계치 보정

기본값 `5000`은 **실측에서 나온 잠정값**이므로 실제 코퍼스로 재보정하세요.

| 문서 | 본문 글자 수 |
|---|---|
| 고시문(허가·변경허가·준공검사) 5건 | 236 ~ 504 |
| 방치선박 제거공고 2건 | 2,357 |
| 전자문서 이용안내(39쪽) | **8,355** ← 제외 대상 |

39쪽인데도 8천자대인 것은 지면 대부분이 스크린샷 이미지(30장)여서입니다 — **쪽수나 파일
크기로 짐작한 값을 쓰면 걸러지지 않습니다.** 표본이 8건뿐이라 이 값은 출발점일 뿐입니다.
`body_chars`가 모든 `schema.json`에 남으므로, 전량을 한 번 돌려 분포를 보고 정하세요.

```bash
java -jar target/extract-pipeline-1.0.0.jar pipeline -i input/ -o out/ --no-db
python3 -c "import json,glob; print(sorted(json.load(open(f))['body_chars'] for f in glob.glob('out/*.schema.json')))"
```

값은 `application.properties`의 `map.max-body-chars`, `.env`의 `MAP_MAX_BODY_CHARS`,
또는 OS 환경변수로 바꿉니다. `0`이면 제한 없이 전부 적재합니다.

## 이미지가 `.bin`으로 저장되는 문제

저장 파일명의 확장자는 `common/ImageFormats`가 정합니다. 예전에는 JPEG/PNG/BMP/GIF **넷만**
알고 나머지를 전부 `.bin`으로 떨어뜨렸는데, 고시류에는 **WMF·EMF·TIFF·OLE 개체**가 일상적으로
들어갑니다. 게다가 세 형식 모두 진짜 확장자를 이미 갖고 있는데 코드가 쓰지 않았습니다.

| 형식 | 컨테이너가 아는 확장자 | 예 |
|---|---|---|
| HWP | BinData 스트림명 | `BIN0001.jpg` |
| HWPX | manifest의 `href` / `mediaType` | `BinData/image1.bmp`, `image/png` |
| HML | `<BINITEM Format="…">` | `Format="jpg"` |

이제 판별은 두 단계입니다.

1. **매직바이트**(1순위) — JPEG/PNG/BMP/GIF에 더해 TIFF·WEBP·WMF·EMF·SVG·ICO와
   OLE 복합문서(`.ole` — 이미지가 아니라 삽입 개체이므로 형식을 그대로 밝힙니다)를 알아봅니다.
   컨테이너 선언은 틀릴 수 있으므로(개명·잘못 저장된 서식) 바이트가 말하는 것이 이깁니다.
2. **컨테이너 확장자**(2순위) — 매직바이트로 못 알아봤을 때만 씁니다. 확장자는 파일 경로가
   되므로 화이트리스트로 검증한 뒤에만 채택합니다.

HML의 `<BINDATA Compress="true">`는 이제 풀어서 저장합니다 — 압축된 채로 두면 확장자 판별이
실패해 `.bin`이 될 뿐 아니라 저장된 파일 자체가 열리지 않습니다.
(HWP는 hwplib이 `BinData` 압축을 이미 풀어 주므로 해당 없음)

둘 다 실패하면 여전히 `.bin`이지만, 이제 **조용히 넘어가지 않습니다**.

```
[경고] 알 수 없는 이미지 형식이라 고시문_img0.bin으로 저장합니다 (매직 0A0B0C0D…, 힌트 없음)
```

남은 `.bin`은 이 경고의 매직바이트로 형식을 특정한 뒤 `ImageFormats`에 서명을 추가하면 됩니다.

## 표를 눈으로 확인하기 (`render`)

매핑 결과가 비었을 때 "표를 잘못 읽었나"와 "사전에 라벨이 없나"를 가르려면 먼저 표가 제대로
뽑혔는지 봐야 합니다. `render`는 `raw.json`의 표를 **원본 병합 구조 그대로** HTML로 되돌립니다.

```bash
java -jar target/extract-pipeline-1.0.0.jar pipeline -i input/ -o out/ --no-db --raw
java -jar target/extract-pipeline-1.0.0.jar render out/ -o out/     # out/<이름>.tables.html
```

`raw.json`의 `grid`는 병합 셀의 텍스트를 덮인 칸마다 반복해 담습니다. 그대로 그리면 원본에서
한 칸이던 것이 세 칸으로 보이므로, `cells`의 span을 살려 `rowspan`/`colspan`으로 접습니다.

```html
<tr><td rowspan="2">승인번호<br>(연월일)</td><td colspan="2">피승인자</td><td rowspan="2">목적</td>…</tr>
<tr><td>주소</td><td>성명</td></tr>
```

**병합 정보가 없으면 추측하지 않습니다.** PDF·OCR 경로는 span이 전부 1이라 "옆 칸과 내용이
같으면 병합"이라는 근사밖에 쓸 수 없는데, 그러면 우연히 값이 같은 이웃 칸까지 접혀 원본에 없던
병합이 생깁니다. 그런 표는 격자 그대로 내고, 표마다 어느 쪽인지 밝힙니다
(`병합 정보 있음(원본 병합 복원)` / `병합 정보 없음(격자 그대로)`).

> `tables` 서브커맨드가 내는 `*.tables.json`은 표 **해석** 결과(라벨:값을 어떻게 읽었는가)이고,
> `render`가 내는 `*.tables.html`은 **추출된 표 그 자체**입니다. 용도가 다릅니다.

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

`ScanOcrRunner`는 스텁 셸 스크립트를 서브프로세스로 실행해 검증하므로
PaddleOCR-VL 설치 없이도 실행됩니다(Windows에서는 해당 테스트 생략).

## 새 형식(확장자)·엔진 통합 절차

1. **detect 등록**: `XyzScanDetector implements ScanDetector` 구현 후
   `DetectorRegistry`에 확장자 매핑 추가 (스캔 변형이 없으면 항상 `false` 스텁).
2. **Extractor 구현**: `engine/xyz/XyzExtractor implements Extractor` —
   `extractRaw`가 raw JSON 계약을 지키도록 구현. 병합 셀은 `cells`에 span을
   채우고, 이미지 저장 시 `path`를 기록. 저장 확장자는
   `ImageFormats.extensionFor(data, hint)`로 정하되 **컨테이너가 아는 확장자를 `hint`로
   넘길 것** — 매직바이트로 못 알아보는 형식이 `.bin`으로 떨어지는 것을 막는다.
   `saveImages`는 `extractRaw`의 이미지와 순서·이름이 동일해야 한다.
3. **레지스트리 연결**: `ExtractorRegistry`에 등록. CLI는 수정 불필요.
4. **동의어 보강**: 새 문서에서 매핑 안 된 라벨이 `extras`에 남으면
   `common/Synonyms`의 `LABEL_SYNONYMS`에 추가.
5. **DB는 수정 불필요**: raw JSON 계약만 지키면 `DbLoader`가 그대로 동작.
6. **테스트**: `src/test/java/.../engine/xyz/`에 `samples/` 기반 회귀 테스트 추가.
