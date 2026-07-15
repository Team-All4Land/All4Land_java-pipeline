# ocr-cli — PaddleOCR-VL 스캔본 OCR (Java 연동용)

Java 파이프라인이 **스캔본**을 만나면 이 Python 스크립트를 **서브프로세스**로 실행해
텍스트·표를 추출한다(`scan/ScanOcrRunner`). 네이티브(텍스트) 문서는 Java가 직접
처리하므로 이 스크립트가 필요 없다.

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
    --output <raw.json 출력 경로> \
    <입력 파일...>
```

- `--file-type pdf` → 입력은 스캔 PDF 원본 1개(파이프라인이 페이지 렌더링)
- 그 외(hwp/hwpx/hml) → 입력은 **Java가 이미 추출해 준 임베디드 스캔 이미지 경로 N개**
  (임베디드 이미지 추출은 Java 담당이므로 이 스크립트는 하지 않는다)
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

## 수동 실행 예시

```bash
# 스캔 PDF
python3 paddleocr_vl_cli.py --source-file 스캔.pdf --file-type pdf \
    --output out.json 스캔.pdf

# 스캔 HWPX에서 Java가 뽑아 둔 이미지들
python3 paddleocr_vl_cli.py --source-file 스캔.hwpx --file-type hwpx \
    --output out.json img0.png img1.png
```

## Java 설정 연결

`src/main/resources/application.properties`:

```properties
ocr.cli.command=python3
ocr.cli.script=ocr-cli/paddleocr_vl_cli.py
ocr.cli.timeout-sec=300
```

- `ocr.cli.command`은 가상환경을 쓰면 해당 venv의 `python` 절대경로로 지정한다.
- 경로는 Java 실행 디렉터리(보통 repo 루트) 기준으로 해석된다.
- 프로세스 실행마다 VLM 모델을 다시 로드하는 비용이 있으므로, 스캔본이 많으면
  타임아웃을 넉넉히 두거나 모델 캐시 준비를 고려한다.
