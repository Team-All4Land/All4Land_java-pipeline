# ocr-cli — PaddleOCR-VL OCR (Java 연동용)

Java 파이프라인이 이미지 안의 글자를 읽어야 할 때 이 Python 스크립트를
**서브프로세스**로 실행한다(`scan/ScanOcrRunner`). 두 경우다.

- **스캔본** — 문서 전체가 이미지라 네이티브 추출로는 아무것도 안 나오는 경우
- **네이티브 문서에 삽입된 사진·위치도** — 본문 일부가 이미지 안에만 있는 경우
  (붙임 현장사진, 위치도, 표 캡처). `scan/ImageOcrEnricher`가 호출한다

원본 Python 파이프라인의 `scan/paddleocr/reader.py` 로직을 발췌한 **독립 실행본**이라
`common`/`hwp`/`hwpx` 패키지 없이 `paddleocr`만 있으면 동작한다.

## 설치

```bash
cd ocr-cli
pip install -r requirements.txt   # 첫 실행 시 VLM 모델(수 GB) 자동 다운로드
```

## 호출 규약 (Java ScanOcrRunner 계약)

```
python3 paddleocr_vl_cli.py \
    --source-file <원본파일명> \
    --file-type <pdf|hwp|hwpx|hml> \
    --input-kind <document|images> \
    --output <raw.json 출력 경로> \
    <입력 파일...>
```

- `--input-kind document` → 입력은 문서 원본 1개(파이프라인이 페이지 렌더링). 현재는 스캔 PDF.
- `--input-kind images` → 입력은 **Java가 이미 추출해 준 이미지 경로 N개**
  (임베디드 이미지 추출은 Java 담당이므로 이 스크립트는 하지 않는다).
  이때만 content 항목에 `source_image`(출처 이미지 파일명)를 붙인다.
- `--input-kind` 생략 시 `--file-type`으로 추정한다(pdf → document, 그 외 → images).
  형식과 입력 해석을 따로 두는 이유: 네이티브 PDF에 삽입된 사진을 읽을 때는
  형식이 pdf이면서 입력은 이미지 목록이다.
- 종료 코드 0 → `--output` 경로에 §4 raw JSON 생성. stdout/stderr는 로그로만 취급.
- 종료 코드 ≠0 → 실패(사유는 stderr). Java가 해당 파일만 `[실패]`로 격리한다.

### 출력 형식 (§4 raw JSON)

```json
{
  "source_file": "고시문.pdf",
  "file_type": "pdf",
  "is_scanned": true,
  "content": [
    {"type": "paragraph", "text": "..."},
    {"type": "table", "n_rows": 2, "n_cols": 3,
     "cells": [{"row": 0, "col": 0, "row_span": 1, "col_span": 1, "text": "..."}],
     "grid": [["..", "..", ".."], ["..", "..", ".."]]}
  ],
  "images": [{"name": "고시문_p0_1.png", "size": 1234}]
}
```

도장(seal)은 제외된 상태다. 이 raw JSON은 Java의 `Mapper`가 그대로 매핑해
표준 스키마로 만든다(네이티브 문서와 같은 경로).

`--input-kind images`면 각 content 항목에 출처가 붙는다:

```json
{"type": "paragraph", "text": "죽천항 59-3 지선", "source_image": "고시문_img0.png"}
```

Java 쪽(`ImageOcrEnricher`)은 이 값을 보고 어느 이미지에서 나온 내용인지 되짚어
원본 raw JSON의 `images[].ocr_text`를 채운다.

## 수동 실행 예시

```bash
# 스캔 PDF (페이지 렌더링)
python3 paddleocr_vl_cli.py --source-file 스캔.pdf --file-type pdf \
    --input-kind document --output out.json 스캔.pdf

# Java가 뽑아 둔 이미지들 (스캔 HWPX의 페이지 이미지 / 네이티브 문서의 사진·위치도)
python3 paddleocr_vl_cli.py --source-file 고시문.hwpx --file-type hwpx \
    --input-kind images --output out.json img0.png img1.png
```

## Java 설정 연결

`src/main/resources/application.properties`:

```properties
ocr.cli.command=python3
ocr.cli.script=ocr-cli/paddleocr_vl_cli.py
ocr.cli.timeout-sec=300
ocr.images.enabled=true          # 네이티브 문서에 삽입된 사진·위치도도 읽을지
ocr.images.min-dimension=400     # 긴 변이 이보다 짧으면 읽지 않음 (도장·로고·서명 제외)
```

- `ocr.cli.command`은 가상환경을 쓰면 해당 venv의 `python` 절대경로로 지정한다.
- 경로는 Java 실행 디렉터리(보통 repo 루트) 기준으로 해석된다.
- 프로세스 실행마다 VLM 모델을 다시 로드하는 비용이 있으므로, 스캔본이 많으면
  타임아웃을 넉넉히 두거나 모델 캐시 준비를 고려한다.
