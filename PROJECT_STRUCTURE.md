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
                └── + 임베디드 이미지는 파일로 저장하고 images 메타만 기록 (§7.1)
                        OCR은 타지 않는다 — 사진 속 글자를 본문에 섞지 않는다
        │
        ▼
raw JSON (공통 계약 §4 — Python 버전과 동일, Jackson DTO)
        ▼
TableInterpreter.interpret (표 서식 판정 + 라벨:값 추출 + 사전 매핑 판정)
        ▼
표 해석 JSON §5.1 {"tables": [...]}   ← 중간 산출물 (`--tables` 지정 시 저장)
        ▼
Mapper.mapToSchema (문단 메타·라벨 + 표 해석 결과 적용 + 값 정규화)
        ▼
표준 스키마 JSON {"source_file", "records": [...], "images": [...]}
        ▼
DbLoader (PostgreSQL JDBC + HikariCP)
        ▼
PostgreSQL (documents / ref_files 2테이블) + images/ 폴더
```

핵심 원칙 유지: **판별(detect) → 추출(engine) → 매핑(common) → 적재(db) 4계층 분리**.

표 해석을 매핑에서 떼어낸 이유: 표준 스키마는 "어느 필드가 무슨 값이 됐는가"만 남기고
표 구조를 버린다. 값이 틀렸을 때 **표를 잘못 읽은 것인지 사전에 라벨이 없는 것인지**
구분할 수 없어, 그 사이를 드러내는 단계를 두었다(§5.1).
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
│   │   ├── DetectCommand.java   #   detect: 스캔 여부 일괄 분류·집계 (--json, --summary)
│   │   ├── ExtractCommand.java  #   extract: 형식/엔진 지정 추출 (+--raw, --no-images)
│   │   ├── MapCommand.java      #   map: raw JSON → 스키마 JSON (매핑 전용)
│   │   └── LoadCommand.java     #   load: 스키마 JSON → PostgreSQL (적재 전용)
│   │
│   ├── detect/                  # ★ 1차 분기: 스캔 판별
│   │   ├── ScanDetector.java    #   인터페이스: boolean isScanned(Path)
│   │   ├── DetectorRegistry.java#   확장자 → 판별기 매핑 (§3)
│   │   ├── ScanSurvey.java      #   문서 집합 스캔 판별 집계 (추출·OCR 없이 1차 분기만)
│   │   ├── PdfScanDetector.java #   첫 페이지 텍스트 레이어·이미지 유무 (PDFBox)
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
│   │   ├── table/               #   표 해석 중간 단계 (§5.1)
│   │   │   ├── TableInterpreter.java # raw 표 → 서식 판정 + 라벨:값 추출
│   │   │   ├── TableGrid.java   #     병합(span) 반영 논리 격자 — 없으면 연속 중복 근사
│   │   │   ├── TableDoc.java    #     *.tables.json 최상위 (+ 미매핑 라벨 요약)
│   │   │   └── InterpretedTable / TableRecord / TableFact / TableColumn / TableKind
│   │   ├── Synonyms.java        #   동의어 사전 로더(resources/synonyms.json) + normalizeLabel
│   │   ├── Labels.java          #   "라벨 : 값" 줄 스캔 + extras 라벨 채택 기준 (문단·표 공용)
│   │   ├── Mapper.java          #   mapToSchema(RawDocument) → SchemaResult
│   │   ├── Heuristics.java      #   고시문 제목 추정(guessTitleFromTables), 캡션 매칭
│   │   ├── Tables.java          #   그리드 유틸 (cleanGrid, gridToTable)
│   │   └── Address.java         #   주소 추출 휴리스틱
│   │
│   ├── docs/                    # ★ 검토용 문서 생성 (사전·미매핑 라벨 리포트)
│   │   ├── SynonymsDoc.java     #   synonyms.json → docs/SYNONYMS.md
│   │   └── ExtrasReport.java    #   *.schema.json 집계 → docs/EXTRAS_REVIEW.md
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
│   │       ├── PdfBoxExtractor.java   # Apache PDFBox — 텍스트, 콘텐츠 스트림 기반 이미지 수집
│   │       └── TableDetector.java     # 선분 클러스터링 표 탐지 (pdfplumber 로직 포팅)
│   │   └── image/                     # 이미지 저장 대상 판정 — 전 엔진 공용 (§4.1)
│   │       ├── ImageContent.java      #   정보량 측정 (잉크 비율·뻗은 범위·붉은 잉크)
│   │       ├── ImageSieve.java        #   정보 없는 이미지·도장 제외 판정 + 임계값
│   │       └── PdfImageTiles.java     #   가로 띠로 쪼개진 타일 병합 (PDF 전용)
│   │
│   ├── scan/                    # ★ OCR 처리 — PaddleOCR-VL CLI 서브프로세스 실행기
│   │   ├── ScanOcrRunner.java   #   ProcessBuilder — CLI 계약(§7)대로 실행, --output JSON 파싱
│   │   └── ScanOcrConfig.java   #   실행 커맨드·스크립트 경로·타임아웃
│   │
│   └── db/                      # ★ 최종 적재 계층
│       ├── DataSourceFactory.java #  HikariCP 커넥션 풀 생성 (환경변수/.env/application.properties)
│       ├── DbSchema.java        #   DDL(§6) 실행 (Flyway 사용 시 마이그레이션은 resources/db/migration/)
│       └── DbLoader.java        #   SchemaResult → documents/ref_files 적재 (PostgreSQL JDBC)
│
├── .env.example                # 리눅스 서버 배포용 환경변수 예시 (.env로 복사; 우선순위 OS 환경변수 > .env > properties)
├── docs/                       # 생성 문서 (dict 서브커맨드 산출물 — 손으로 고치지 않는다)
│   ├── SYNONYMS.md             #   동의어 사전 검토 문서
│   └── EXTRAS_REVIEW.md        #   미매핑 라벨 빈도 + 표준 필드 채움률
├── src/main/resources/
│   ├── application.properties   # db.url=jdbc:postgresql://... , 풀 설정, ocr.cli.* 등 (기본값)
│   ├── synonyms.json           # ★ 동의어 사전 본문 (단일 정의처 — 설명·예시 포함)
│   └── db/migration/           # Flyway 마이그레이션 (V1__init.sql = §6 DDL, V2 = ref_files 절대경로, 이후 누적)
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
| PDF | **첫 페이지**의 텍스트 레이어 유무 (이미지 유무 함께 확인) | PDFBox |
| HWP | 본문 텍스트 유무 (문단·표 셀·중첩 표·글상자·캡션 집계) | hwplib |
| HWPX | 본문 텍스트 유무 (`<hp:t>`, 머리말·꼬리말 제외) | java.util.zip + StAX |
| HML | 본문 텍스트 유무 (`<CHAR>`, 머리말·꼬리말 제외) | StAX |

판정 규칙은 네 형식 공통으로 한 줄이다:

```
스캔본 = 네이티브 본문 텍스트가 한 글자도 없다 AND 임베디드 이미지가 1개 이상
```

적용 범위만 형식별로 다르다. HWP/HWPX/HML은 문서 흐름 모델이라 페이지가 렌더링 시점에
결정되므로 **문서 전체**를 집계하고, PDF는 페이지가 물리적 단위여서 **첫 페이지**에만 적용한다.

PDF에서 첫 페이지만 보는 이유는 이 절의 원칙 자체다. 예전에는 전 페이지의 텍스트 레이어
유무를 세어 텍스트 페이지 비율이 절반 미만이면 스캔본으로 봤는데, 그 기준은 사실상 지면
점유율 판정이어서 아래에서 배제한 신호를 되살린 것과 같았다 — 붙임 위치도·현장사진이 페이지
절반을 넘는 네이티브 고시 PDF가 스캔본으로 오판됐다. 대가는 명시해 둔다: 혼합 문서에서 첫
장이 네이티브면 뒤쪽 스캔 페이지의 본문은 버려지고(텍스트 없는 페이지마다 `PdfBoxExtractor`가
경고를 남겨 로그로 드러난다), 반대로 첫 장이 스캔이면 뒤쪽 네이티브 페이지도 전부 OCR로 간다.
§7 계약이 문서 단위 라우팅이라 페이지별로 섞어 처리할 수 없다.

**이미지의 개수·크기·면적은 판정 근거가 아니다.** 사진이 지면 대부분을 차지한다는 것만으로는
스캔본이 아니다 — 붙임 현장사진·위치도처럼 본문이 사진으로 채워진 네이티브 문서가 흔하다.
이미지 유무는 "OCR로 얻을 게 있는가"를 확인하는 전제 조건일 뿐이다.

지면 점유율도 신호가 되지 않는다. 본문 전체가 이미지 한 장인 문서와 붙임 사진이 여러 장
들어간 문서를 실측하면 둘 다 **본문 폭의 96~100%, 인쇄 영역의 약 40%**로 구분되지 않는다.
기하 조건을 추가하면 판별력은 안 늘고 오판 경로만 는다.

임계치가 한 글자라 판별의 부담은 전부 **"본문 텍스트를 빠짐없이, 그리고 본문만 세는가"**로
옮겨간다. 컨테이너 하나를 빠뜨리면 사진 문서가 스캔본으로(hwplib의 표 셀 문단이 실제로 그랬다),
머리말·꼬리말을 본문으로 세면 진짜 스캔본이 네이티브로 뒤집힌다.

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
    {"name": "고시문_img0.png", "path": "/srv/extract/out/images/고시문_img0.png",
     "size": 1234, "ocr_text": null}
  ]
}
```

- `content`는 문서 등장 순서 유지 (문단·표 혼재).
- 표는 `grid` 필수, `cells`(병합 셀 span)는 선택 — HmlExtractor·OwpmlExtractor가 채운다.
- 이미지 `path`(저장된 이미지의 절대경로)는 DB 적재(`ref_files.file_path`)에 필요하므로 필수.
  `ocr_text`는 선택 필드 — 현재 Java 파이프라인은 채우지 않지만 계약 호환을 위해 유지.
- 이미지 추출 대상 판정(§4.1): 정보가 없는 이미지·도장은 저장하지 않는다.
  스캔본은 CLI 측 seal 레이블에 맡긴다.
- **JSON 필드명은 snake_case 유지** (Python 산출물과 바이트 수준 호환 —
  `@JsonProperty` 또는 SNAKE_CASE 네이밍 전략 적용).

## 4.1. 이미지 추출 대상 판정 (engine/image/)

저장되는 첨부는 **문서에 실제로 그려진, 내용이 있는, 온전한 한 장**이어야 한다.
그 셋이 각각 깨졌던 것이 실사용에서 나온 세 가지 결함이다.

| 결함 | 원인 | 대응 |
|---|---|---|
| 정보 없는 이미지가 첨부로 나옴 | 어느 엔진에도 내용 유무를 보는 판정이 없었다 | `ImageSieve` 정보량 판정 |
| 사진 한 장이 여러 장으로 쪼개짐 | PDF를 리소스 딕셔너리로만 훑어 배치를 몰랐다 | `PdfImageTiles` 띠 병합 |
| 도장이 계속 저장됨 | 크기·붉은색 판정 기준이 셋 다 틀렸다 | `ImageSieve` 도장 판정 |

**정보량 판정** (`ImageSieve` — PDF/HWP/HWPX/HML 공용).
배경색을 흰색이 아니라 **양자화 최빈색**으로 잡고, 배경과 다른 픽셀을 '잉크'로 센다
(`ImageContent`). 아래 중 하나라도 걸리면 버린다.

| 규칙 | 조건 |
|---|---|
| 균일 | 잉크 없음 |
| 점 하나 | 잉크 1% 미만 **이면서** 잉크가 뻗은 범위가 가로·세로 어느 쪽으로도 25% 미만 |
| 극소 | 24px 미만, 또는 지면 크기를 알 때 3mm 미만 |

'점 하나' 규칙에서 두 조건의 AND가 안전장치다. 선이 드문 위치도·도면도 잉크량은
표식과 비슷하지만, 선이 지면을 가로질러 뻗으므로 걸리지 않는다.

이 판정은 **거르는 쪽이 아니라 살리는 쪽으로 기울여** 두었다. 첨부가 하나 더 붙는 것보다
본문 도면이 사라지는 쪽이 훨씬 나쁘고, 후자는 눈에 띄지도 않기 때문이다. 실측 기준으로
'점 하나'는 잉크 0.31%·범위 6%, 살려야 할 도면 중 가장 성긴 것이 잉크 0.32%·범위 97%로,
두 부류를 가르는 것은 사실상 **뻗은 범위** 한 축이다(6% ↔ 97%, 임계 25%). 잉크량 축은
거의 겹쳐 있으므로 그것만으로는 가를 수 없다.

측정에서 이 성질을 깨뜨리는 함정이 셋 있었고, 셋 다 **도면을 지우는 방향**으로 틀렸다.

- **표본을 성기게 잡으면 1px 가는 선을 통째로 건너뛴다.** 괘선만 있는 위치도가 '잉크 없음'
  으로 측정됐다. 그래서 4천만 픽셀까지는 전 픽셀을 훑는다(행 단위 `getRGB`).
- **뻗은 범위를 바운딩박스 *면적*으로 재면 얇고 긴 선이 한구석에 몰린 것처럼 보인다.**
  지면을 가로지르는 선 하나가 면적으로는 0.3%다. 가로·세로 각각의 변 길이 비 중
  **큰 쪽**을 쓴다.
- **색 수를 조건에 넣으면 흑백 도면이 부류째 걸린다.** 흑백 선도면은 색이 2개뿐이고
  잉크도 1% 미만이라 '2색 표식' 규칙에 정확히 들어맞았다. 그 규칙은 제거했다 —
  앵커·크롭마크는 '점 하나'가 이미 잡는다.

**띠 병합** (`PdfImageTiles` — PDF 전용).
PDF 생성기가 큰 래스터를 가로 띠로 쪼개 넣으면 지도 한 장이 첨부 N건이 된다.
맞닿아 있고 변이 일치하는 배치를 수렴할 때까지 병합한다. 서로 다른 사진을 잘못 붙이지
않도록 픽셀 밀도(2% 이내)·비트수·컬러스페이스 일치, 합집합이 정확한 직사각형,
회전·기울임 제외를 모두 요구한다. 병합본은 무손실 PNG로 저장하고,
병합이 없는 단일 이미지는 원본 스트림 바이트를 그대로 쓴다(JPEG 재인코딩 회피).
HWP 계열 BinData는 그림이 통째로 들어 있어 이 문제가 없다 — PDF에만 적용한다.

**도장 판정**. 예전 기준은 세 가지가 모두 틀렸고, 그래서 도장이 통과했다.

- PDF 쪽 컬러스페이스 성분 수를 봤다 → **인덱스(팔레트)·그레이 도장이 영원히 통과**했다.
  디코딩된 이미지는 이미 RGB이므로 그것만 본다.
- 붉은색 우세를 **흰 배경 포함 전체 평균**으로 쟀다 → 흰 바탕에 가는 붉은 선인 도장은
  임계를 못 넘는다. 이제 **잉크 픽셀 대비** 붉은 픽셀 비율(≥55%)로 잰다.
- 크기를 **픽셀**로 쟀다(≤300px) → 고해상도 스캔 도장은 그냥 통과했다.
  이제 지면 기준(≤45mm)으로 보고, 지면 크기를 모르는 HWP 계열만 픽셀로 대체한다.

여기에 종횡비(0.6~1.7)와 잉크 비율(0.5~60%)을 더해 전부 만족할 때만 도장으로 본다.
스텐실 마스크(ImageMask)는 잉크 색이 이미지가 아니라 그래픽 상태에 있으므로,
현재 비스트로크 색으로 칠한 뒤 판정한다 — 그러지 않으면 붉은 도장이 검정으로 보인다.

**PDF는 콘텐츠 스트림에서 이미지를 모은다** (`PDFGraphicsStreamEngine`).
리소스 딕셔너리만 보면 페이지에 그려지지 않는 이미지까지 저장되고, 배치를 몰라 띠를
되붙일 수 없다. 맨 `PDFStreamEngine`은 연산자를 등록하지 않아 변환 행렬이 항등으로
남으므로 `PDFGraphicsStreamEngine`을 써야 한다.

임계값은 전부 `ImageSieve` 상단에 모여 있다. 제외·병합은 배치 로그를 남기지 않는다 —
이미지가 있는 문서마다 줄이 붙어 정작 봐야 할 `[실패]`가 묻힌다. 특정 이미지가 왜
걸렸는지 확인해야 하면 `ImageContent.measure`를 직접 불러 수치를 본다.
디코딩 자체가 안 되는 이미지는 조용히 사라지면 알 수 없으므로 `[경고]`로 남긴다.

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

## 5.1. 표 해석 중간 산출물 (`*.tables.json`)

`TableInterpreter.interpret(raw)`의 출력. **DB에 적재되지 않는 진단용 산출물**이며,
`pipeline --tables` 또는 `tables` 서브커맨드로 저장한다.

고시문은 정보 대부분이 표에 있고, 표 서식이 기관마다 다르다. 표준 스키마만 보면
값이 왜 비었는지(표를 잘못 읽었나 / 라벨이 사전에 없나) 알 수 없으므로,
**표를 어떻게 읽었는지를 그대로 남긴다.**

```json
{
  "source_file": "고시양식.hml", "file_type": "hml", "engine": "hml-dom",
  "summary": { "table_count": 2, "fact_count": 9,
               "mapped_count": 7, "unmapped_count": 2,
               "unmapped_labels": ["공작물의종류", "수면의종류"] },
  "tables": [{
    "index": 1, "kind": "label_value", "n_rows": 9, "n_cols": 2,
    "header_rows": 0, "span_aware": false,
    "columns": [],
    "records": [{ "row": -1, "fields": [
      { "origin": "label_pair", "row": 2, "col": 1,
        "raw_label": "점용·사용 장소", "label": "점용·사용장소",
        "canonical": "location", "mapped": true,
        "value": "인천광역시 동구 만석동 2-174번지 인근 공유수면" },
      { "origin": "label_pair", "row": 7, "col": 1,
        "raw_label": "공작물의 종류", "label": "공작물의종류",
        "canonical": null, "mapped": false, "value": "선가대(6기)" }
    ]}]
  }]
}
```

| 필드 | 의미 |
|---|---|
| `kind` | `header_list`(헤더+데이터 목록표) / `label_value`(라벨·값 서식표) / `unknown` |
| `header_rows` | 목록표에서 헤더로 쓴 행 수 (2단 병합 헤더면 2) |
| `span_aware` | 병합 정보로 정확히 읽었는지. `false`면 내용 비교 근사(PDF·OCR 경로) |
| `columns` | 목록표의 열별 헤더 해석 (열 인덱스 → 표준 필드) |
| `records[].row` | 목록표는 원본 데이터 행 인덱스, 서식표는 `-1`(문서 전체 기여) |
| `fields[].origin` | `label_pair`(라벨/값 칸 쌍) / `in_cell`(셀 안 "라벨: 값") / `header_column` |
| `fields[].row`/`col` | 값이 있던 **원본 격자 좌표** (병합 접기 전 기준) |
| `fields[].canonical` | 매핑된 표준 필드. `null`이면 미매핑 → `extras`로 간다 |

표 읽기 규칙:

1. **목록표** — 상단 1~2행의 셀 중 60% 이상이 표준 필드로 매핑되면 헤더로 보고,
   이후 각 행을 독립 레코드로 만든다. 2단 병합 헤더는 열마다 아래쪽(더 구체적인)
   헤더를 우선한다. 헤더는 잡혔는데 데이터 행에서 아무 값도 못 얻으면 서식표로 되돌린다.
2. **서식표 라벨/값 칸 쌍** — 값은 **라벨 바로 다음 칸만** 본다. 빈 칸을 건너뛰며
   값을 찾으면 병합 셀로 값 칸이 빈 서식에서 옆 필드의 라벨을 값으로 삼킨다.
3. **셀 안 라벨:값** — 문서 전체가 1열 표(테두리 박스) 안에 든 서식용.

병합 셀 처리: 엔진이 `cells`에 span을 채웠으면(hml·hwpx) 셀 경계를 정확히 알고 접는다.
span이 없으면(PDF·OCR) 옆 칸과 내용이 같은 것을 병합으로 간주하는 근사로 접고,
그 사실을 `span_aware: false`로 표시한다.

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
    file_path     TEXT NOT NULL    -- 저장 이미지의 절대경로 (V2 마이그레이션에서 상대→절대로 의미 변경)
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
- 실행 커맨드·스크립트 경로·타임아웃은 `ocr.cli.command`/`ocr.cli.script`/
  `ocr.cli.timeout-sec`(또는 `.env`의 `OCR_CLI_COMMAND`/`OCR_CLI_SCRIPT`/
  `OCR_CLI_TIMEOUT_SEC`)에서 읽는다.
- 스크립트 부재·비정상 종료·타임아웃 시 해당 파일만 `[실패]` 로그 후 다음 파일로
  계속 (배치 격리 원칙 유지). 타임아웃 시 프로세스를 강제 종료한다.
- VLM 추론은 오래 걸릴 수 있으므로 타임아웃은 파일당 수 분 단위로 넉넉히.
  프로세스 실행마다 모델을 다시 로드하는 비용이 있으므로, 스캔본이 많아지면
  스크립트 측에서 모델 캐시 등으로 완화한다.

### 7.1 네이티브 문서의 임베디드 이미지는 OCR하지 않는다

**OCR 경로는 스캔 판정(§3) 파일 전용이다.** 네이티브 문서에 붙임 현장사진·위치도·표
캡처가 들어 있어도 `PipelineSupport`는 그 이미지를 `images/`에 저장하고 `images` 메타
(`ref_files` 적재용)만 기록한다 — 서브프로세스를 띄우지 않는다.

한동안은 네이티브 경로에서도 삽입 이미지를 OCR해 본문 뒤에 이어 붙였다. 그 방식은
다음을 대가로 치렀고, 그래서 걷어냈다.

- **스캔이 아닌 문서가 OCR을 탄다**: `is_scanned=false`로 적재된 문서인데 처리 중에는
  Python VLM이 돌아, 로그·소요 시간만 보면 스캔 오판처럼 보인다. 판정 결과와 실제
  실행 경로가 어긋나는 상태였다.
- **배치 시간이 문서 내용에 좌우된다**: 이미지가 있는 모든 네이티브 문서가 대상이라
  도장·로고를 걸러내는 이미지 크기 임계값이 필요했고, 그 값 하나가 배치 전체의 소요
  시간을 갈랐다.
- **본문 순서를 보증할 수 없다**: 이미지가 몇 번째 문단에 붙어 있었는지는 hwplib 등에서
  복원할 수 없어 결과를 본문 맨 뒤에 몰아 붙일 수밖에 없었다.

그 대가로 포기하는 것은 분명히 적어 둔다: **본문이 사진 안에만 있는 네이티브 문서는 그
내용이 추출되지 않는다.** 사진 속 텍스트가 필요해지면 별도 단계로 다루고(적재된
`ref_files.file_path`로 이미지에 접근할 수 있다), 추출 본문에 섞지 않는다.

- **실패 등급**: 스캔본은 이미지가 유일한 본문 출처라 OCR 실패를 `[실패]`로 격리한다.
  네이티브 경로에는 OCR이 없으므로 스크립트 부재가 영향을 주지 않는다.

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
| `pipeline -i input/ -o out/ [--no-db] [--raw] [--tables]` | 배치: 판별→추출→표해석→매핑→적재 일괄 (기존 pipeline.mjs 대체) |
| `detect 파일... [--json] [--summary]` | 스캔 여부 분류·집계 (판별만 실행 — 추출·OCR 없음) |
| `extract 파일... -o out/ [--raw] [--no-images] [--engine hwplib]` | 추출+매핑 (엔진 강제 지정 가능) |
| `tables raw.json -o out/ [--summary]` | 표 해석 전용 (raw JSON → 표 해석 JSON §5.1) |
| `map raw.json -o out/` | 매핑 전용 (raw JSON → 스키마 JSON) |
| `load out/*.schema.json` | DB 적재 전용 (재적재·스키마 변경 시 단독 실행) |
| `dict [-o docs/SYNONYMS.md] [--review out/]` | 동의어 사전·미매핑 라벨 검토 문서 생성 (§9.1) |

- 공통 옵션 의미는 Python 버전과 동일 (`--raw`: 원시 결과 포함,
  `--tables`: 표 해석 중간 결과 포함, `--no-images`: 이미지 저장 생략).
- **DB 접속·OCR 실행 정보는 OS 환경변수 > `.env` > `application.properties`
  순으로 읽는다**(`db.*`, `ocr.cli.*`) — CLI 재정의 옵션은 두지 않는다.
  리눅스 서버 배포 시에는 `.env.example`을 `.env`로 복사해 관리한다.
  비밀번호는 파일에 평문으로 두지 말고 `.env`나 환경변수(`PGPASSWORD` 등)로
  주입한다.
- 파일마다 `out/<이름>.schema.json` (+`--raw` 시 `<이름>.raw.json`) 생성.
- 파일 단위 실패는 로그만 남기고 다음 파일로 계속 진행 (배치 격리).

`pipeline` 내부 동작:

```
1) input/ 재귀 스캔 → .hwp/.hwpx/.hml/.pdf 파일 목록 수집
2) 스캔본이 있으면 OCR 스크립트(ocr.cli.script) 존재 확인 (없으면 스캔 파일만 [실패] 처리)
3) 파일별: DetectorRegistry 판별
     스캔본  → (HWP/HWPX/HML이면 임베디드 이미지 추출 후) ScanOcrRunner 서브프로세스 → raw JSON
     네이티브 → ExtractorRegistry에서 확장자 매칭 Extractor → raw JSON
                + 임베디드 이미지가 있으면 같은 경로로 OCR해 본문 뒤에 이어 붙임 (§7.1)
4) TableInterpreter.interpret → (--tables 시) out/<이름>.tables.json 저장
5) Mapper.mapToSchema(표 해석 결과 재사용) → out/<이름>.schema.json 저장
6) DbLoader로 documents/ref_files 적재
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
   `src/main/resources/synonyms.json`에 추가 (절차는 §9.1).
5. **DB는 수정 불필요**: raw JSON 계약만 지키면 `DbLoader`가 그대로 동작.
6. **테스트**: `src/test/resources/fixtures/`에 픽스처 추가, JUnit 회귀 테스트 작성.
   Python 버전 픽스처를 공유해 두 구현의 결과를 교차 검증.
7. **문서화**: README 형식 표에 행 추가.

## 9.1. 동의어 사전 보강 절차

기관마다 같은 필드를 다른 라벨로 부른다(`고시일자` / `공고일자` / `공시일자`).
문서를 일일이 보고 판별하는 대신, **매핑 안 된 라벨을 전량 모아 빈도로 판단**한다.

사전 본문은 `src/main/resources/synonyms.json` 하나다. Java 코드에 사전을 두지 않는
이유는 검토자가 코드를 열지 않고 고칠 수 있어야 하고, 필드 설명·예시를 사전과 같은
곳에 두어야 문서와 사전이 어긋나지 않기 때문이다.

```
1) 전량 처리        java -jar extract.jar pipeline -i input/ -o out/ --tables
2) 리포트 생성      java -jar extract.jar dict --review out/
                    → docs/SYNONYMS.md       (사전 검토 문서)
                    → docs/EXTRAS_REVIEW.md  (미매핑 라벨 빈도 + 필드 채움률)
3) 검토·판단        빈도 높은 라벨이 기존 필드와 같은 뜻인가?
                      같음   → 해당 필드의 synonyms에 추가
                      다름   → 여러 기관에서 반복되면 새 표준 컬럼 승격 검토
                      드묾   → extras에 두고 다음 검토 때 재확인
4) 사전 수정        src/main/resources/synonyms.json
5) 회귀 확인        mvn test   (중복 등재는 기동 시 오류로 중단됨)
6) 재적재           pipeline 재실행 — 파일 단위 멱등 적재라 기존 문서도 갱신되어
                    extras에 있던 값이 표준 컬럼으로 승격된다
```

**표본이 아니라 전량을 세는 이유**: 라벨 표기 습관은 기관 단위로 몰려 다닌다.
일부만 표본으로 보면 특정 기관의 표기가 통째로 빠져, 그 기관 문서에서만 필드가
비는 현상이 뒤늦게 드러난다.

**충돌 방지**: 같은 라벨이 두 필드에 등재되면 어느 쪽으로 매핑될지가 등재 순서에
좌우된다. 사전 로드 시 정규화 결과가 중복되면 **기동을 중단**시켜 배포 전에 드러낸다.

**값이 이상할 때**: 라벨이 깨져 보이거나 값이 문장 조각이면 사전 문제가 아니라
표 읽기 문제일 수 있다. 해당 문서의 `*.tables.json`(§5.1)에서 원본 좌표와
서식 판정(`kind`, `span_aware`)을 확인한다.

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
