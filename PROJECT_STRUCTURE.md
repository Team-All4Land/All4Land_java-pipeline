# 프로젝트 구조 — Java 기반 (PROJECT_STRUCTURE_JAVA)

공유수면 점용·사용 고시류 문서(**HWP / HWPX / HML / PDF — 네이티브·스캔본 모두**)에서
텍스트·표·이미지를 추출하고, 표준 필드 스키마로 정규화한 뒤 **PostgreSQL DB에 적재**하는
파이프라인의 **Java 구현** 구조.

파이프라인 흐름·계약(raw JSON, 표준 스키마, DB 적재)은 Python 버전과 동일하다.
앞단에는 크롤러가 붙어 수집 결과 엑셀과 첨부파일 폴더를 내놓는다. 엑셀은 시트 3장이고
그중 **수집결과**가 게시물(`OS_NOTI_BAS`)을, **검증요약**이 기관(`OS_INSTT_BAS`)과 크롤
기록(`OS_CRWL_LOG_DTL`)을 채운다. 특이사항 시트는 수집결과의 파생이라 적재하지 않는다.
기관·게시물의 정의처는 이 엑셀이고, 폴더명 파싱은 수동 수집분 폴백이다. 전자관보 게시물만은
지자체명이 한 값("전자관보")이라 비고에서 발령 기관을 읽는다(`SourceFolder.gazetteAgencyOf`).
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
표준 스키마 JSON {"atch_file_nm", "atch_file_path", "records": [...], "images": [...]}
        ▼
DbLoader (PostgreSQL JDBC + HikariCP)
        ▼
PostgreSQL (OS_INSTT_BAS → OS_NOTI_BAS → OS_ATCH_FILE_DTL → OS_NOTI_ITEM_VAL_DTL) + images/ 폴더
                    └ 기관별 크롤 기록은 OS_CRWL_LOG_DTL (OS_INSTT_BAS로 FK)
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
│   │   ├── DetectCommand.java   #   detect: 스캔 여부 일괄 분류·집계 (--json, --summary, --failures)
│   │   ├── ExtractCommand.java  #   extract: 형식/엔진 지정 추출 (+--raw, --no-images)
│   │   ├── RenderCommand.java   #   render: raw JSON의 표 → HTML 복원 (검수용, §5.2)
│   │   ├── MapCommand.java      #   map: raw JSON → 스키마 JSON (매핑 전용)
│   │   └── LoadCommand.java     #   load: 스키마 JSON → PostgreSQL (적재 전용)
│   │
│   ├── detect/                  # ★ 1차 분기: 스캔 판별
│   │   ├── ScanDetector.java    #   인터페이스: boolean isScanned(Path)
│   │   ├── DetectorRegistry.java#   확장자 → 판별기 매핑 (§3)
│   │   ├── ScanSurvey.java      #   문서 집합 스캔 판별 집계 (추출·OCR 없이 1차 분기만)
│   │   ├── FailureKind.java     #   판별 실패의 갈래 (구버전/배포용/암호/손상/OOM…) (§3.1)
│   │   ├── FailureClassifier.java#  매직바이트 + 원인 체인으로 갈래 판정 (§3.1)
│   │   ├── EncryptionProbe.java #   배포용·암호 문서 판정 (컨테이너 직접 확인, §3.1)
│   │   ├── PdfScanDetector.java #   첫 페이지 텍스트 레이어·이미지 유무 (PDFBox)
│   │   ├── HwpScanDetector.java #   네이티브 텍스트량 vs 임베디드 이미지 비중 (hwplib)
│   │   ├── HwpxScanDetector.java#   ZIP 내 본문 XML 텍스트 검사
│   │   └── HmlScanDetector.java #   XML 본문 텍스트 vs base64 BinData 비중
│   │
│   ├── common/                  # ★ 형식 공통 계층
│   │   ├── model/               #   Jackson DTO — 계약의 단일 정의처
│   │   │   ├── RawDocument.java #     atch_file_nm / atch_file_path / file_extn_nm / scan_yn / content / images
│   │   │   ├── RawParagraph.java, RawTable.java, RawCell.java, RawImage.java
│   │   │   ├── NoticeRecord.java#     15개 표준 필드 + extras (java record)
│   │   │   └── SchemaResult.java#     atch_file_nm / atch_file_path / records / images / body_char_cnt / excl_rsn_ctnt
│   │   ├── table/               #   표 해석 중간 단계 (§5.1)
│   │   │   ├── TableInterpreter.java # raw 표 → 서식 판정 + 라벨:값 추출
│   │   │   ├── TableGrid.java   #     병합(span) 반영 논리 격자 — 없으면 연속 중복 근사
│   │   │   ├── TableRenderer.java#    raw 표 → HTML 복원 (병합은 rowspan/colspan, §5.2)
│   │   │   ├── TableDoc.java    #     *.tables.json 최상위 (+ 미매핑 라벨 요약)
│   │   │   └── InterpretedTable / TableRecord / TableFact / TableColumn / TableKind
│   │   ├── Errors.java          #   예외 → 원인 체인까지 담은 사유 문장 (모든 [실패] 로그의 출처)
│   │   ├── ImageFormats.java    #   저장 확장자 판별 — 매직바이트 + 컨테이너 힌트 폴백 (§4.2)
│   │   ├── DocumentSize.java    #   본문 글자 수 측정 (문단 + 표 셀, 공백 제외)
│   │   ├── LoadPolicy.java      #   적재 여부 판정 — 안내문류 차단 (map.max-body-chars, §5)
│   │   ├── NoticeItems.java        #   공고항목 사전 로더(resources/notice_items.json) + normalizeLabel
│   │   ├── Labels.java          #   "라벨 : 값" 줄 스캔 + extras 라벨 채택 기준 (문단·표 공용)
│   │   ├── Mapper.java          #   mapToSchema(RawDocument) → SchemaResult
│   │   ├── Heuristics.java      #   고시문 제목 추정(guessTitleFromTables), 캡션 매칭
│   │   ├── Tables.java          #   그리드 유틸 (cleanGrid, gridToTable)
│   │   └── Address.java         #   주소 추출 휴리스틱
│   │
│   ├── docs/                    # ★ 검토용 문서 생성 (사전·미매핑 라벨 리포트)
│   │   ├── NoticeItemsDoc.java     #   notice_items.json → docs/NOTICE_ITEMS.md
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
│       ├── DbLoader.java        #   SchemaResult → 7테이블 적재 (PostgreSQL JDBC)
│       │                        #   excl_rsn_ctnt이 있는 파일은 항목값만 건너뜀
│       └── LoadStats.java       #   적재 요약 (성공/실패/항목값 적재제외 건수)
│
├── .env.example                # 리눅스 서버 배포용 환경변수 예시 (.env로 복사; 우선순위 OS 환경변수 > .env > properties)
├── docs/                       # 생성 문서 (dict 서브커맨드 산출물 — 손으로 고치지 않는다)
│   ├── NOTICE_ITEMS.md             #   동의어 사전 검토 문서
│   └── EXTRAS_REVIEW.md        #   미매핑 라벨 빈도 + 표준 필드 채움률
├── src/main/resources/
│   ├── application.properties   # db.url=jdbc:postgresql://... , 풀 설정, ocr.cli.* 등 (기본값)
│   ├── notice_items.json           # ★ 공고항목 사전 (단일 정의처 — 동의어·설명·예시 포함)
│   └── db/migration/           # Flyway 마이그레이션 (V1__init.sql = §6 DDL — 도메인 18 + 7테이블)
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

### 3.1. 판별 실패의 분류 (detect/FailureKind, FailureClassifier)

판별 실패는 **총계로 세면 아무것도 못 한다.** 한글 3.0 구버전(변환하면 살릴 수 있다), 암호
문서(원본을 다시 받아야 한다), 손상 파일(포기한다)은 대응이 전혀 다른데 예전에는 셋이 한
덩어리였다. 게다가 판별기가 원인 예외를 `UncheckedIOException`으로 감싸고 `ScanSurvey`가
`getMessage()`만 남겨, 사유가 `"HWP 스캔 판별 실패: <경로>"` 한 줄로 뭉개졌다.

두 계층으로 나눠 푼다.

- `common/Errors.describe(Throwable)` — 원인 체인을 끝까지 이어 붙인다(중복 메시지는 한 번만,
  순환 참조 방어, 최대 깊이 8). 배치의 모든 `[실패]` 로그가 이것을 쓴다.
- `detect/FailureClassifier.classify(Path, Throwable)` — `FailureKind`로 분류한다.

분류 근거는 둘이고, **매직바이트가 예외 메시지보다 신뢰할 수 있다.** 라이브러리는 "읽지
못했다"까지만 말하지만, 파일 앞 64바이트를 직접 보면 한글 3.0(`HWP Document File`)·
CFB(`D0CF11E0…`)·ZIP(`PK\x03\x04`)·PDF(`%PDF`)가 갈린다. 전량 배치에서도 비용이 없다.

#### 배포용 문서 (`DISTRIBUTION_LOCKED`) — `detect/EncryptionProbe`

**"한글에서는 열리는데 파이프라인은 못 읽는다"의 정체다.** 배포용 문서는 본문이 디스크
상에서 실제로 암호화돼 있다. 한글은 열쇠를 갖고 있어 아무것도 묻지 않고 열어 주므로
사용자 눈에는 멀쩡한 파일이고, 그래서 "정상 파일인데 왜 실패하냐"가 반복해서 올라온다.

라이브러리는 이 상황을 암호라고 말하지 않는다 — hwplib은 `"This is not paragraph."`,
hwpxlib은 `"Invalid byte 1 of 1-byte UTF-8 sequence"`를 던진다. 예외 메시지로 분류하던
동안 이 문서들은 각각 `DOCUMENT_PARSE`·`XML_PARSE`로 묻혔다. **재저장 한 번이면 살아날
문서가 고칠 수 없는 손상과 같은 칸에 세어진 것이다.**

그래서 `EncryptionProbe`가 컨테이너를 직접 본다.

| 형식 | 판정 근거 |
|---|---|
| HWP 5.0 | `FileHeader` offset 36 속성 비트 — bit2 배포용, bit1 사용자 암호 |
| HWPX | `META-INF/manifest.xml`의 `<odf:encryption-data>` + `Contents/content.hpf`의 `hpf:distribution="1"` |

`DISTRIBUTION_LOCKED`(재저장하면 살아난다)와 `ENCRYPTED`(암호를 아는 사람에게 해제본을
받아야 한다)는 **대응이 다르므로 갈래를 나눈다.** 판별기·추출기 진입점은
`EncryptionProbe.requireUnlocked(Path)`로 먼저 걸러, 원인 체인 첫 문장부터 읽을 수 있게 한다.

자동 복호화는 하지 않는다 — 배포용 해제는 수동으로(한글에서 일반 문서로 다시 저장) 처리한다.

`ScanSurvey.of`는 `Exception`이 아니라 **`Throwable`**을 잡는다 — 파일 하나의
`OutOfMemoryError`가 수만 건 배치를 통째로 죽이면 안 된다.

집계는 `ScanSurvey.byFailureKind(sampleLimit)`가 갈래별 건수와 예시 파일로 낸다.
`detect --failures <경로>`는 실패 전건을 JSON으로 떨궈 재처리 대상을 그대로 넘길 수 있게 한다.

#### 갈래별 대응

무엇이 실패했는지는 `docs/DB_STANDARD.md`의 `CD_FAIL_KND`가 코드와 이름으로 갖고 있다.
여기 적는 것은 **그래서 무엇을 해야 하는가**다 — 코드 사전이 담을 수 없는 정보다.

| 갈래 | 뜻 | 대응 |
|---|---|---|
| `HWP3_LEGACY` | 한글 3.0인데 자체 파서가 못 읽음 | 손상 여부 확인(구버전이라서가 아님) |
| `NOT_COMPOUND_FILE` | `.hwp`인데 아는 서명이 하나도 없음 | 헤더 손상 또는 지원 대상 아닌 형식 |
| `NOT_ZIP` | `.hwpx`인데 아는 서명이 하나도 없음 | 헤더 손상 또는 지원 대상 아닌 형식 |
| `PASSWORD_PROTECTED` | 열기 암호(HWP 암호설정 비트 / HWPX PBKDF2) | 암호 해제본 확보 — **한글에서도 안 열림** |
| `DRM_PROTECTED` | DRM 보안 문서 | **사용자도 못 엶** — DRM 해제본 확보 |
| `DISTRIBUTION_UNSUPPORTED` | 배포용인데 본문 해석 실패 | 개별 확인 — 암호 문제가 아님 |
| `ENCRYPTED` | 암호화는 분명한데 갈래 미상 | 원본 상태 확인 |
| `ZIP_CORRUPT` / `XML_PARSE` | 컨테이너·본문 XML 손상 | 원본 재수집 |
| `DOCUMENT_PARSE` | 컨테이너는 정상, 내부 구조에서 깨짐 | 개별 확인(라이브러리 미지원 레코드일 수 있음) |
| `PDF_LOAD` | PDF를 열지 못함 | 서명 유무를 상세에서 확인 |
| `EMPTY_FILE` / `FILE_ACCESS` | 0바이트 / 경로·권한 문제 | 수집 단계 점검 |
| `OUT_OF_MEMORY` | 파일 하나가 힙을 다 씀 | `-Xmx` 상향 또는 해당 파일 제외 |

갈래는 **파일 앞부분의 매직바이트**(라우팅과 같은 `DocFormat`)와 **원인 체인의 맨 끝 예외**

`OUT_OF_MEMORY`가 갈래로 있는 이유는 `ScanSurvey.of`가 `Exception`이 아니라 `Throwable`을
잡기 때문이다. 파일 하나가 힙을 다 써도 그 건만 세고 배치는 계속 간다.

## 4. 공통 계약: raw JSON 형식 (Python 버전과 동일)

모든 추출 경로(네이티브 Extractor, OCR CLI 결과)는 아래 형식을 출력해야 한다.
Java에서는 `common/model/RawDocument.java`(Jackson)가 이 계약의 단일 정의처다.

```json
{
  "atch_file_nm": "원본파일명.ext",
  "atch_file_path": "/srv/input/원본파일명.ext",
  "file_extn_nm": "hwp",
  "scan_yn": false,
  "content": [
    {"type": "paragraph", "text": "문단 텍스트"},
    {"type": "table", "n_rows": 2, "n_cols": 8,
     "caption": "<표 1> 공유수면 점용·사용허가 내역",
     "grid": [["행", "별"], ["셀", "텍스트"]],
     "cells": [{"row": 0, "col": 0, "row_span": 1, "col_span": 1, "text": "행"}]}
  ],
  "images": [
    {"name": "고시문_img0.png", "path": "/srv/extract/out/images/고시문_img0.png",
     "size": 1234, "caption": "[그림 2] 현장 위치도", "ocr_text": null}
  ]
}
```

- `content`는 문서 등장 순서 유지 (문단·표 혼재).
- 표는 `grid` 필수, `cells`(병합 셀 span)는 선택 — HmlExtractor·OwpmlExtractor가 채운다.
- `caption`(표·이미지)은 선택 필드다. **캡션이 없으면 키 자체가 나오지 않는다**
  (`@JsonInclude(NON_NULL)`) — 그래야 캡션 없는 문서의 산출물이 이 필드가 생기기 전과
  바이트 단위로 같아 Python 호환이 유지된다(§4.2).
- 이미지 `path`(저장된 이미지의 절대경로)는 DB 적재(`OS_ATCH_IMG_DTL.IMG_FILE_PATH`)에 필요하므로 필수.
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

### 4.2. 저장 확장자 판별 (common/ImageFormats)

`.bin`은 "판별에 실패했다"는 뜻이지 "손상됐다"는 뜻이 아니다. 예전에는 JPEG/PNG/BMP/GIF
넷만 알고 나머지를 전부 `.bin`으로 떨어뜨렸는데, 고시류에는 WMF·EMF·TIFF·OLE 개체가
일상적으로 들어간다.

판별은 두 순위다.

1. **매직바이트** — 위 넷에 TIFF/WEBP/WMF/EMF/SVG/ICO와 OLE 복합문서(`ole`)를 더했다.
   OLE는 이미지가 아니라 삽입 개체(수식·차트)이므로 이미지 확장자를 붙이지 않고 형식을
   그대로 밝힌다. 뷰어가 깨진 그림으로 오해하는 것보다 낫다.
2. **컨테이너가 선언한 확장자** — 매직바이트로 못 알아봤을 때만. 세 형식 모두 이 정보를
   갖고 있는데 예전에는 쓰지 않았다: HWP는 BinData 스트림명(`BIN0001.jpg`), HWPX는
   manifest의 `href`/`mediaType`, HML은 `<BINITEM Format="…">`.
   확장자는 파일 경로가 되므로 화이트리스트 검증 후에만 채택한다.

순서가 이 방향인 이유: 컨테이너 선언은 개명·잘못 저장된 서식에서 틀릴 수 있으므로 바이트가
말하는 것이 이긴다. 다만 스니핑이 실패했을 때 **대안이 아예 없던 것**이 `.bin`의 실체였다.

HML의 `<BINDATA Compress="true">`는 풀어서 저장한다 — 압축된 채로 두면 확장자 판별이
실패할 뿐 아니라 저장된 파일 자체가 열리지 않는다. 속성이 없어도 zlib처럼 보이면 한 번
시도하고, 풀리지 않으면 원본을 그대로 쓴다(손해가 없다). HWP는 hwplib이 이미 풀어 준다.

둘 다 실패하면 `.bin`을 유지하되 `[경고]`에 매직바이트와 힌트를 적는다 — 남은 사례를
특정해 서명을 추가할 수 있어야 한다.

## 4.2. 표·그림 캡션

캡션은 "이 표가 무엇인가"를 담은 유일한 정보인데 계약에 자리가 없어 지금까지 버려졌다.
hwp3 엔진만 읽고 있었고, 담을 곳이 없어 일반 문단으로 흘려보냈다 — 그러면 **어느 표의
캡션인지 알 수 없어** 검수에서도 매핑에서도 쓸 수 없다. `RawTable.caption`·
`RawImage.caption`을 두고, 캡션은 **문단이 아니라 전용 필드에만** 담는다.

| 엔진 | 표 캡션 | 그림 캡션 |
|---|---|---|
| `HwplibExtractor` | `ControlTable.getCaption()` | `ControlPicture.getCaption()` → `binItemID`로 BinData와 연결 |
| `OwpmlExtractor` | `Table.caption()` | `Picture.caption()` |
| `HmlExtractor` | `TABLE/SHAPEOBJECT/CAPTION` | `PICTURE/SHAPEOBJECT/CAPTION` |
| `Hwp3Extractor` | `Hwp3Document.Table.caption()` | — (아래) |
| `PdfBoxExtractor` | `Heuristics.nearestCaption` (추정) | — |

두 군데는 **일부러 붙이지 않는다.**

- **hwp3 그림** — 임베디드 이미지가 본문과 떨어진 파일 태그 영역에 있고 이어 줄 식별자가
  없다. 등장 순서로 짝지으면 `ImageSieve`가 걸러 낸 이미지 때문에 색인이 어긋난다.
  **엉뚱한 사진에 캡션이 붙는 것은 캡션이 없는 것보다 나쁘다.** 지금처럼 문단으로 남긴다.
- **PDF 그림** — PDF에는 캡션이라는 구조가 아예 없다. 표에만 위치 기반 추정을 적용한다.

`HwplibExtractor`의 그림 캡션은 본문 순회로 `binItemID → 캡션` 맵을 먼저 만든다. **표 셀·
묶음 안까지 재귀해야 한다** — 고시류는 현장사진이 표 안에 들어가는 일이 흔해, 최상위
문단만 보면 정작 캡션이 붙은 사진을 전부 놓친다. BinData 스트림명(`BIN0001.jpg`)의 번호는
**16진수**다(10진수로 읽으면 항목이 16개를 넘는 순간 캡션이 엉뚱한 그림에 붙는다).

캡션은 `DocumentSize.bodyChars`가 본문으로 세고(안 그러면 문단에서 뺀 만큼 분량이 줄어
적재 판정이 소리 없이 달라진다), `TableRenderer`가 `<caption>`으로 낸다.

## 5. 표준 스키마 (common/model/NoticeRecord.java)

`Mapper.mapToSchema(raw)`의 출력은 `{"atch_file_nm", "atch_file_path", "records": [...], "images": [...]}`.
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

`SchemaResult`(파일 단위 묶음)에는 **적재 판단**도 함께 실린다.

| 필드 | 의미 |
|---|---|
| `body_char_cnt` | 본문 글자 수 (`common/DocumentSize.bodyChars` — 문단 + 표 셀, 공백 제외) |
| `excl_rsn_ctnt` | 적재를 건너뛰는 사유. 없으면 필드 자체가 빠진다(적재 대상) |

판단을 매핑 단계(`common/LoadPolicy`)에 두고 결과를 스키마 JSON에 남기는 이유는,
`pipeline`으로 한 번에 돌리든 `map` → `load`로 나눠 돌리든 **같은 파일이 같은 결정**을
받아야 하기 때문이다. `DbLoader.loadAll`이 이 값을 보고 건너뛰므로 두 경로가 갈리지 않는다.

기준은 본문 **글자 수**다. 쪽수는 형식마다 의미가 다르고(HWP 계열은 추출 결과에 쪽 개념이
없다), 파일 크기는 이미지 용량에 좌우돼 본문 분량과 무관하다. 실측한 39쪽 안내문이 8,355자밖에
안 되는 것이 그 증거다 — 지면 대부분이 스크린샷이라 쪽수로 짐작한 값으로는 걸러지지 않는다.

추출·매핑은 정상 수행하고 **적재만** 건너뛴다. 임계치를 잘못 잡아 진짜 고시문이 걸려도
`raw.json`·`schema.json`이 남아 있어 `load`로 다시 넣으면 된다. 그래서 임계치는 고시문 쪽에
여유를 두고 잡는다 — 안내문 한 건이 더 들어오는 쪽이 고시문 한 건이 빠지는 쪽보다 낫다.
값은 `map.max-body-chars`(기본 5000, `0`이면 제한 없음)로 조정한다.

## 5.1. 표 해석 중간 산출물 (`*.tables.json`)

`TableInterpreter.interpret(raw)`의 출력. **DB에 적재되지 않는 진단용 산출물**이며,
`pipeline --tables` 또는 `tables` 서브커맨드로 저장한다.

고시문은 정보 대부분이 표에 있고, 표 서식이 기관마다 다르다. 표준 스키마만 보면
값이 왜 비었는지(표를 잘못 읽었나 / 라벨이 사전에 없나) 알 수 없으므로,
**표를 어떻게 읽었는지를 그대로 남긴다.**

```json
{
  "atch_file_nm": "고시양식.hml", "file_extn_nm": "hml", "engine": "hml-dom",
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

## 5.2. 표 복원 산출물 (`*.tables.html` — `render` 서브커맨드)

§5.1이 "표를 **어떻게 읽었는가**"라면 이쪽은 "**무엇이 뽑혔는가**"다. 매핑 결과가 비었을 때
표를 잘못 읽은 것인지 사전에 라벨이 없는 것인지 가르려면, 그 이전에 **표가 제대로 뽑혔는지**
부터 봐야 한다. `common/table/TableRenderer`가 `raw.json`의 표를 HTML로 되돌린다.

`grid`는 병합 셀의 텍스트를 덮인 칸마다 반복해 담으므로 그대로 그리면 원본에서 한 칸이던
것이 세 칸으로 보인다. `cells`의 span을 살려 앵커 셀에만 `rowspan`/`colspan`을 내고 덮인
칸은 생략한다(판정 기준은 `TableGrid.of`와 동일).

```html
<tr><td rowspan="2">승인번호<br>(연월일)</td><td colspan="2">피승인자</td><td rowspan="2">목적</td>…</tr>
<tr><td>주소</td><td>성명</td></tr>
```

**병합 정보가 없으면 추측하지 않는다.** PDF·OCR 경로는 span이 전부 1이라 §5.1의 연속 중복
근사밖에 쓸 수 없는데, 검수용 산출물에서 원본에 없던 병합을 만들어 보여 주는 쪽이 있는
구조를 못 보여 주는 쪽보다 나쁘다. 그런 표는 격자 그대로 내고 표마다 어느 쪽인지 밝힌다.

서브커맨드 이름이 `table`이 아니라 `render`인 이유: 기존 `tables`와 한 글자 차이면 오타 한
번에 엉뚱한 명령이 돈다.

## 6. DB 스키마 (PostgreSQL — resources/db/migration/)

기관 → 게시물 → 첨부파일 → (처분) 항목값의 4계층 EAV 스키마이고, 옆에 크롤 실행 기록이
하나 붙는다. `DbLoader`가 PostgreSQL JDBC 드라이버로 적재하고, `V1__init.sql` 하나가
DB 표준 사전(`resources/db/standard_terms.json`)에 맞춰 표준도메인 20개와 9테이블을 세운다. 스키마를 고칠
때는 파일을 얹지 않고 `V1`을 고친 뒤 DB를 다시 만든다(§6.5).

**ERD와 엔터티 목록은 [README의 데이터 모델 절](README.md#데이터-모델)에 있다.** 여기서는 구성만 적는다 — 같은 표를 두 문서에 옮겨 적으면 반드시 한쪽이 뒤처진다.

### 6.1 이름 규칙

물리명은 `[수식어] + … + [분류어]` 조합이고 마지막 낱말(분류어)이 도메인을 지시한다
(`NM` → `D_NM` = `VARCHAR(300)`). 테이블은 `[업무코드]_[의미]_[유형 접미사]`이고 업무코드는
해양공간을 뜻하는 `OS`다(데이터 표준화 지침 OFBD-2210-01 §3.2.3). 제약조건·인덱스는
`[테이블명]_PK` · `[테이블명]_FK01` · `[테이블명]_IX01` 형식이며
기준 `_BAS` · 명세 `_DTL` · 코드 `_TC`를 쓴다. 낱말·도메인·용어·코드는 전부
`standard_terms.json`이 단일 정의처이고, `DbStandardTest`가 DDL과 대조한다.

### 6.2 구성

**기관** `OS_INSTT_BAS` — 고시·공고를 수집한 기관 게시판. 입력 폴더 하나가 한 행이다

| 논리명 | 물리명 | 도메인 | |
|---|---|---|---|
| 기관일련번호 | `INSTT_SN` | `D_SN` | PK
| 기관명 | `INSTT_NM` | `D_NM` |
| 기관종류코드 | `INSTT_KND_CD` | `D_CD` |
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |

**고시공고게시물** `OS_NOTI_BAS` — 게시물 1건. 크롤러 게시물 목록 엑셀 한 줄이 한 행이며, 같은 게시물의 첨부를 묶는 키를 겸한다

| 논리명 | 물리명 | 도메인 | |
|---|---|---|---|
| 고시공고일련번호 | `NOTI_SN` | `D_SN` | PK · 엑셀의 번호
| 기관일련번호 | `INSTT_SN` | `D_SN` |
| 게시상태코드 | `BBS_STTS_CD` | `D_CD` |
| 원문키내용 | `SRC_KEY_CTNT` | `D_CTNT` |
| 원문키해시 | `SRC_KEY_HASH` | `D_HASH` | UNIQUE · 중복 수집 차단
| 게시물URL | `BBS_URL` | `D_URL` |
| 게시물제목 | `BBS_TTL` | `D_TTL` |
| 크롤종류코드 | `CRWL_KND_CD` | `D_CD` |
| 담당부서명 | `CHRG_DEPT_NM` | `D_NM` |
| 담당자명 | `CHRG_PSN_NM` | `D_NM` |
| 전화번호 | `TEL_NO` | `D_NO` |
| 고시번호 | `NOTI_NO` | `D_NO` | 게시판이 표기한 값
| 고시일자 | `NOTI_DT` | `D_DT` | 게시판이 표기한 값
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |

크롤러가 준 컬럼에는 `NOT NULL`이 없다 — 크롤 산출물이 아닌 파일은 음수 `NOTI_SN`으로
같은 테이블에 들어오고 그 행에는 원문키도 URL도 없다.

**크롤로그** `OS_CRWL_LOG_DTL` — 하루치 수집에서 기관 하나를 긁은 결과

| 논리명 | 물리명 | 도메인 | 엑셀 출처 |
|---|---|---|---|
| 크롤로그일련번호 | `CRWL_LOG_SN` | `D_SN` | PK
| 기관일련번호 | `INSTT_SN` | `D_SN` | FK · 번호
| 크롤일자 | `CRWL_DT` | `D_DT` | 이 수집이 돈 날
| 크롤종류코드 | `CRWL_KND_CD` | `D_CD` | (엑셀에 없음)
| 크롤상태코드 | `CRWL_STTS_CD` | `D_CD` | 판정
| 크롤단계코드 | `CRWL_STEP_CD` | `D_CD` | 실패 시
| 기관게시판URL | `INSTT_BBS_URL` | `D_URL` | 사이트 URL
| 고시공고건수 | `NOTI_CNT` | `D_CNT` | 고시공고 건수
| 첨부파일건수 | `ATCH_FILE_CNT` | `D_CNT` | 첨부파일 다운로드 건수
| 실패메시지내용 | `FAIL_MSG_CTNT` | `D_CTNT` | 실패 시
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |

크롤러만 아는 것(자기 집계·상태·언제 돌았나)만 담는다. 수집 기간은 `min/max(OS_NOTI_BAS.NOTI_DT)`로
그대로 나오고, 게시상태는 운영 단계에서 전부 `POST`라 가를 값이 없어 둘 다 빼냈다 —
게시완료 게시판까지 뒤진 것은 과거 데이터를 전수로 긁던 첫 수집 때뿐이고 그건 이미 끝났다.
성공도 행으로 남긴다 — 실패만 적재하면 "돌았는데 새 고시가 없었다"와 "아예 안 돌았다"가
둘 다 "행 없음"이 돼 조용한 수집 누락을 못 잡는다. `INSTT_SN`은 FK다: 이 레포는 결과가
0건이어도 기관을 등록하므로(`AgencyRegistry`) FK가 막을 일이 없고, 그래서 기관명을 여기
중복해 담지 않는다.

**공고종류** `OS_NOTI_KND_TC` — 공고종류 56종. 한 기관이 평균 12종을 발행하므로 기관으로는 종류를 구분할 수 없다

| 논리명 | 물리명 | 도메인 | |
|---|---|---|---|
| 공고종류코드 | `NOTI_KND_CD` | `D_CD` | PK
| 공고종류명 | `NOTI_KND_NM` | `D_NM` |
| 상위공고종류명 | `HRNK_NOTI_KND_NM` | `D_NM` |
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |

**공고항목** `OS_NOTI_ITEM_TC` — 표준항목 40종. notice_items.json이 단일 정의처이며 ReferenceSync가 기동 시 upsert한다

| 논리명 | 물리명 | 도메인 | |
|---|---|---|---|
| 공고항목코드 | `NOTI_ITEM_CD` | `D_CD` | PK
| 공고항목명 | `NOTI_ITEM_NM` | `D_NM` |
| 항목계열명 | `ITEM_SRS_NM` | `D_NM` |
| 항목값유형코드 | `ITEM_VAL_TY_CD` | `D_CD` |
| 주요항목여부 | `CORE_ITEM_YN` | `D_YN` |
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |

**첨부파일** `OS_ATCH_FILE_DTL` — 파일 1건. 원본 절대경로, 문서 단위 메타(고시번호·고시일자·제목)와 추출 상태

| 논리명 | 물리명 | 도메인 | |
|---|---|---|---|
| 고시공고일련번호 | `NOTI_SN` | `D_SN` | PK
| 첨부일련번호 | `ATCH_SN` | `D_SN` | PK
| 첨부파일명 | `ATCH_FILE_NM` | `D_NM` |
| 첨부파일경로 | `ATCH_FILE_PATH` | `D_PATH` |
| 처리상태코드 | `PROC_STTS_CD` | `D_CD` |
| 실패단계코드 | `FAIL_STEP_CD` | `D_CD` |
| 실패종류코드 | `FAIL_KND_CD` | `D_CD` |
| 실패메시지내용 | `FAIL_MSG_CTNT` | `D_CTNT` |
| 적재제외사유내용 | `EXCL_RSN_CTNT` | `D_CTNT` |
| 파일확장자명 | `FILE_EXTN_NM` | `D_NM` |
| 실제파일확장자명 | `ACTL_FILE_EXTN_NM` | `D_NM` |
| 스캔여부 | `SCAN_YN` | `D_YN` |
| 추출엔진명 | `EXTC_ENGN_NM` | `D_NM` |
| 공고종류코드 | `NOTI_KND_CD` | `D_CD` |
| 고시번호 | `NOTI_NO` | `D_NO` |
| 고시일자 | `NOTI_DT` | `D_DT` |
| 고시제목 | `NOTI_TTL` | `D_TTL` |
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |

**첨부이미지** `OS_ATCH_IMG_DTL` — 이미지는 처분 레코드가 아니라 첨부파일의 속성이다 — 한 파일이 레코드 N건을 낳을 때 어느 레코드에 붙일지 정할 근거가 없다

| 논리명 | 물리명 | 도메인 | |
|---|---|---|---|
| 고시공고일련번호 | `NOTI_SN` | `D_SN` | PK
| 첨부일련번호 | `ATCH_SN` | `D_SN` | PK
| 이미지일련번호 | `IMG_SN` | `D_SN` | PK
| 이미지캡션내용 | `IMG_CPTN_CTNT` | `D_CTNT` |
| 이미지파일경로 | `IMG_FILE_PATH` | `D_PATH` |
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |

**공고항목값** `OS_NOTI_ITEM_VAL_DTL` — 항목값. 40개 표준항목을 전부 동등하게 행으로 담는다

| 논리명 | 물리명 | 도메인 | |
|---|---|---|---|
| 고시공고일련번호 | `NOTI_SN` | `D_SN` | PK
| 첨부일련번호 | `ATCH_SN` | `D_SN` | PK
| 처분일련번호 | `DSPS_SN` | `D_SN` | PK
| 공고항목코드 | `NOTI_ITEM_CD` | `D_CD` | PK
| 반복일련번호 | `RPT_SN` | `D_SN` | PK
| 항목값내용 | `ITEM_VAL_CTNT` | `D_CTNT` |
| 최초등록일시 | `FRST_REG_DTM` | `D_DTM` |
| 최종변경일시 | `LAST_CHG_DTM` | `D_DTM` |

### 6.3 적재 규칙

- **멱등 단위는 첨부파일이다.** 게시물 단위로 지우면 파일을 한 건씩 처리하는 도중 같은
  게시물의 앞선 첨부가 함께 날아간다. 첨부 행을 지우면 CASCADE로 이미지와 항목값이 함께
  정리되므로 재적재가 안전하다.
- **실패·항목값 적재제외도 행으로 남긴다**(`PROC_STTS_CD`). 성공만 적재하면 "첨부 401건 중 추출
  0건"인 기관이 DB에서 아예 보이지 않는다.
- **사전 동기화(`ReferenceSync`)가 적재보다 먼저** 돈다 — `OS_NOTI_ITEM_VAL_DTL`이
  `OS_NOTI_ITEM_TC`를 참조하므로 순서가 뒤바뀌면 첫 적재가 FK 위반으로 실패한다.
  기관도 같은 이유로 첨부보다 먼저 넣는다(`OS_NOTI_BAS.INSTT_SN`가 FK).
- **스키마 마이그레이션은 Flyway**로 관리한다(`resources/db/migration/V*.sql`). 런타임에
  `ALTER TABLE`로 컬럼을 자동 추가하지 않고 버전 파일을 얹어 이력을 남긴다.
- 배치 적재는 **트랜잭션 + `addBatch`/`executeBatch`**로 수행하고, 파일 단위 실패는
  세이브포인트 롤백 후 다음 파일로 계속 진행한다(배치 격리).

### 6.4 자주 쓰는 질의

미수집 첨부는 컬럼이 아니라 **첨부순번 결번**으로 잡습니다:

```sql
SELECT NOTI_SN, count(*) AS 수집, max(ATCH_SN) AS 최대순번
  FROM OS_ATCH_FILE_DTL GROUP BY NOTI_SN HAVING count(*) <> max(ATCH_SN);
```

어느 라벨을 사전에 올릴지는 이 한 줄로 고릅니다 — 전에는 `dict --review`로 문서를 다시 만들어야
보이던 것입니다:

```sql
SELECT ITEM_LBL_NM, count(*) AS 건수, count(DISTINCT NOTI_SN) AS 문서수
  FROM OS_NOTI_LBL_VAL_DTL GROUP BY 1 ORDER BY 2 DESC LIMIT 20;
```

기관별 수집 현황은 폴더명이 채운 `INSTT_SN`로 봅니다:

```sql
-- 기관·게시판별 수집·성공 건수
SELECT A.INSTT_NM, A.INSTT_KND_CD, N.BBS_STTS_CD,
       count(*) AS 첨부, count(*) FILTER (WHERE F.PROC_STTS_CD = 'OK') AS 성공
  FROM OS_ATCH_FILE_DTL F JOIN OS_NOTI_BAS N USING (NOTI_SN) JOIN OS_INSTT_BAS A USING (INSTT_SN)
 GROUP BY 1, 2, 3 ORDER BY 1, 3;

-- 긁긴 했는데 첨부가 한 건도 없는 기관
SELECT A.INSTT_NM FROM OS_INSTT_BAS A
  LEFT JOIN OS_NOTI_BAS N USING (INSTT_SN) WHERE N.NOTI_SN IS NULL;

-- 기관이 안 붙은 게시물 = 입력 루트 직속이거나 폴더명 규약 위반
SELECT count(*) FROM OS_NOTI_BAS WHERE INSTT_SN IS NULL;
```

"긁긴 했는데 아무것도 못 건진 기관"과 "아예 못 긁은 기관"은 크롤로그로 갈립니다 —
첨부 테이블만 봐서는 둘이 똑같이 0건으로 보입니다:

```sql
-- 어느 단계에서 몇 번 넘어졌나
SELECT A.INSTT_NM, L.CRWL_STEP_CD, count(*) AS 실패
  FROM OS_CRWL_LOG_DTL L LEFT JOIN OS_INSTT_BAS A USING (INSTT_SN)
 WHERE L.CRWL_STTS_CD = 'FAIL' GROUP BY 1, 2 ORDER BY 3 DESC;

-- 크롤은 성공했는데 게시물이 한 건도 안 생긴 기관
SELECT DISTINCT A.INSTT_NM
  FROM OS_CRWL_LOG_DTL L JOIN OS_INSTT_BAS A USING (INSTT_SN)
  LEFT JOIN OS_NOTI_BAS N USING (INSTT_SN)
 WHERE L.CRWL_STTS_CD = 'OK' AND N.NOTI_SN IS NULL;

-- 크롤러가 지금까지 수집했다고 집계한 누계 vs 실제 적재된 누계 = 수집 누락 후보.
-- 두 쪽을 각각 접은 뒤 견준다 — 로그와 게시물을 곧장 조인하면 행이 서로 곱해진다.
WITH 크롤러 AS (SELECT INSTT_SN, sum(NOTI_CNT) AS 집계 FROM OS_CRWL_LOG_DTL GROUP BY 1),
     실제   AS (SELECT INSTT_SN, count(*) AS 건수
                  FROM OS_NOTI_BAS WHERE INSTT_SN IS NOT NULL GROUP BY 1)
SELECT A.INSTT_NM, c.집계, coalesce(r.건수, 0) AS 실제
  FROM 크롤러 c JOIN OS_INSTT_BAS A USING (INSTT_SN)
  LEFT JOIN 실제 r USING (INSTT_SN)
 WHERE c.집계 <> coalesce(r.건수, 0);

-- 오늘 안 돈 기관 = 스케줄 누락
SELECT A.INSTT_NM FROM OS_INSTT_BAS A
 WHERE NOT EXISTS (SELECT 1 FROM OS_CRWL_LOG_DTL L
                    WHERE L.INSTT_SN = A.INSTT_SN AND L.CRWL_DT = current_date);
```

08.07 산출물을 실제로 밀어 넣으면 첫 질의가 **2건**을 돌려줍니다 — 영광군청 465/466과
양양군청 120/121입니다. 나머지 71개 기관은 크롤러 집계와 적재 행수가 정확히 맞습니다.

### 6.5 개발 DB 초기화

스키마를 고쳤거나(=`V1__init.sql`을 손댔거나) 이전 버전 스키마가 남아 있으면 DB를 비우고
다시 만듭니다. 이관 SQL은 없습니다 — 파이프라인이 원본 파일에서 전건을 다시 만들어 내므로
DB는 파생물입니다.

```bash
# 1) 스키마를 통째로 비우고 다시 만든다
psql -U extract -d extract -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'

# 2) 파이프라인이 기동하면서 Flyway가 V1을 적용한다 (DDL을 손으로 돌릴 일은 없습니다)
java -jar target/extract-pipeline-1.0.0.jar pipeline -i input -o out
```

**테이블만 지우면 안 됩니다.** 표준도메인(`D_SN`·`D_CD` …)과 `FC_OS_ISO_DATERANGE` 함수는 테이블이
아니라 스키마 객체라 `DROP TABLE`로는 사라지지 않고, 남아 있으면 재적용이 이렇게 죽습니다:

```
ERROR: type "d_sn" already exists
```

Flyway 이력 테이블(`flyway_schema_history`)도 함께 지워져야 `V1`이 다시 돕니다.
`DROP SCHEMA public CASCADE`는 이 셋을 한 번에 처리합니다.

비었는지 확인하려면:

```
\dt    -- 테이블 0개 ("Did not find any relations.")
\dD    -- 도메인 0개
\df    -- 함수 0개
```

**`must be owner of schema public`이 나면** 스키마 소유자가 아닙니다. 슈퍼유저로 한 번
넘겨주거나, 아래처럼 DB를 통째로 다시 만듭니다.

```bash
psql -U postgres -d extract -c 'ALTER SCHEMA public OWNER TO extract;'
```

**DB를 통째로 다시 만들 때**는 접속 세션이 남아 있으면 `DROP DATABASE`가 실패하므로 먼저
끊습니다.

```bash
psql -U postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                      WHERE datname = 'extract' AND pid <> pg_backend_pid();"
psql -U postgres -c 'DROP DATABASE extract;' \
                 -c 'CREATE DATABASE extract OWNER extract;'
```

> **되돌리기는 없습니다.** Flyway Community에는 undo가 없으므로 스키마를 고치는 길은 위
> 재생성 하나뿐입니다. `V1`을 고쳤는데 DB를 안 지우면 Flyway가 체크섬 불일치로 **적재 전에**
> 멈추므로 데이터는 건드리지 않습니다 — 아래 메시지를 보면 DB를 다시 만들면 됩니다.
>
> ```
> Validate failed: Migrations have failed validation
> Migration checksum mismatch for migration version 1
> ```


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
              (도장은 seal 레이블로 제외된 상태. scan_yn는 Java가 true로
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
(`OS_ATCH_IMG_DTL` 적재용)만 기록한다 — 서브프로세스를 띄우지 않는다.

한동안은 네이티브 경로에서도 삽입 이미지를 OCR해 본문 뒤에 이어 붙였다. 그 방식은
다음을 대가로 치렀고, 그래서 걷어냈다.

- **스캔이 아닌 문서가 OCR을 탄다**: `scan_yn=false`로 적재된 문서인데 처리 중에는
  Python VLM이 돌아, 로그·소요 시간만 보면 스캔 오판처럼 보인다. 판정 결과와 실제
  실행 경로가 어긋나는 상태였다.
- **배치 시간이 문서 내용에 좌우된다**: 이미지가 있는 모든 네이티브 문서가 대상이라
  도장·로고를 걸러내는 이미지 크기 임계값이 필요했고, 그 값 하나가 배치 전체의 소요
  시간을 갈랐다.
- **본문 순서를 보증할 수 없다**: 이미지가 몇 번째 문단에 붙어 있었는지는 hwplib 등에서
  복원할 수 없어 결과를 본문 맨 뒤에 몰아 붙일 수밖에 없었다.

그 대가로 포기하는 것은 분명히 적어 둔다: **본문이 사진 안에만 있는 네이티브 문서는 그
내용이 추출되지 않는다.** 사진 속 텍스트가 필요해지면 별도 단계로 다루고(적재된
`OS_ATCH_IMG_DTL.IMG_FILE_PATH`로 이미지에 접근할 수 있다), 추출 본문에 섞지 않는다.

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
| `render raw.json\|폴더 -o out/` | 표 복원 전용 (raw JSON → HTML, 검수용 — §5.2) |
| `map raw.json -o out/` | 매핑 전용 (raw JSON → 스키마 JSON) |
| `load out/*.schema.json` | DB 적재 전용 (재적재·스키마 변경 시 단독 실행) |
| `dict [-o docs/NOTICE_ITEMS.md] [--review out/]` | 동의어 사전·미매핑 라벨 검토 문서 생성 (§9.1) |

- 공통 옵션 의미는 Python 버전과 동일 (`--raw`: 원시 결과 포함,
  `--tables`: 표 해석 중간 결과 포함, `--no-images`: 이미지 저장 생략).
- 실패 진단 옵션: `--failures <경로>`(실패 건만 JSON으로 저장 — 실패 파일은 `schema.json`이
  아예 생기지 않아 입력과 출력의 차집합을 손으로 떠야 했다), `--stacktrace`.
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
2) ScanSurvey.of(files) — 판별을 배치 전체에서 딱 한 번만 돈다
     (예전에는 OCR 사전 점검이 전 파일을 판별하고 extractOne이 파일마다 또 판별해
      파싱 비용이 정확히 두 배였다. 수만 건 배치에서 그대로 배치 시간이 된다)
3) 스캔본이 있으면 OCR 스크립트(ocr.cli.script) 존재 확인 (없으면 스캔 파일만 [실패] 처리)
4) 파일별: 2)의 판별 결과로 라우팅
     스캔본  → (HWP/HWPX/HML이면 임베디드 이미지 추출 후) ScanOcrRunner 서브프로세스 → raw JSON
     네이티브 → ExtractorRegistry에서 확장자 매칭 Extractor → raw JSON
                + 임베디드 이미지가 있으면 같은 경로로 OCR해 본문 뒤에 이어 붙임 (§7.1)
5) TableInterpreter.interpret → (--tables 시) out/<이름>.tables.json 저장
6) Mapper.mapToSchema(표 해석 결과 재사용) → LoadPolicy.apply → out/<이름>.schema.json 저장
7) DbLoader로 7테이블 적재 (excl_rsn_ctnt이 있는 파일은 [항목값 적재제외])

단계마다 실패를 `[실패] <파일>: (<단계>) <사유>`로 격리한다. 단계는 판별/추출/표해석/매핑/저장
다섯이고, 사유는 `Errors.describe`로 원인 체인까지 담는다(§3.1). `Exception`이 아니라
`Throwable`을 잡아 `OutOfMemoryError`도 파일 단위로 격리한다.
```

## 9. 새 확장자(형식)·엔진 통합 절차

새 형식 `xyz`(또는 기존 형식의 새 엔진)를 추가할 때:

1. **detect 등록**: `XyzScanDetector implements ScanDetector` 구현 후
   `DetectorRegistry`에 확장자 매핑 추가 (스캔 변형이 없으면 항상 `false` 반환 스텁).
2. **Extractor 구현**: `engine/xyz/XyzExtractor implements Extractor` —
   `extractRaw`가 §4 계약(`RawDocument`)을 지키도록 구현. 병합 셀을 복원할 수 있으면
   `cells`에 span을 채우고, 이미지 저장 시 `path`를 반드시 기록.
   저장 확장자는 `ImageFormats.extensionFor(data, hint)`로 정한다 — 컨테이너가 아는 확장자를
   `hint`로 넘겨야 매직바이트로 못 알아보는 형식이 `.bin`으로 떨어지지 않는다 (§4.2).
3. **레지스트리 연결**: `ExtractorRegistry`에 등록. CLI·pipeline은 수정 불필요
   (확장자 라우팅이 레지스트리 기반이므로).
4. **동의어 보강**: 새 문서에서 매핑 안 된 라벨이 `extras`에 남으면
   `src/main/resources/notice_items.json`에 추가 (절차는 §9.1).
5. **DB는 수정 불필요**: raw JSON 계약만 지키면 `DbLoader`가 그대로 동작.
6. **테스트**: `src/test/resources/fixtures/`에 픽스처 추가, JUnit 회귀 테스트 작성.
   Python 버전 픽스처를 공유해 두 구현의 결과를 교차 검증.
7. **문서화**: README 형식 표에 행 추가.

## 9.1. 동의어 사전 보강 절차

기관마다 같은 필드를 다른 라벨로 부른다(`고시일자` / `공고일자` / `공시일자`).
문서를 일일이 보고 판별하는 대신, **매핑 안 된 라벨을 전량 모아 빈도로 판단**한다.

사전 본문은 `src/main/resources/notice_items.json` 하나다. Java 코드에 사전을 두지 않는
이유는 검토자가 코드를 열지 않고 고칠 수 있어야 하고, 필드 설명·예시를 사전과 같은
곳에 두어야 문서와 사전이 어긋나지 않기 때문이다.

```
1) 전량 처리        java -jar extract.jar pipeline -i input/ -o out/ --tables
2) 리포트 생성      java -jar extract.jar dict --review out/
                    → docs/NOTICE_ITEMS.md       (사전 검토 문서)
                    → docs/EXTRAS_REVIEW.md  (미매핑 라벨 빈도 + 필드 채움률)
3) 검토·판단        빈도 높은 라벨이 기존 필드와 같은 뜻인가?
                      같음   → 해당 필드의 synonyms에 추가
                      다름   → 여러 기관에서 반복되면 새 표준 컬럼 승격 검토
                      드묾   → extras에 두고 다음 검토 때 재확인
4) 사전 수정        src/main/resources/notice_items.json
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
