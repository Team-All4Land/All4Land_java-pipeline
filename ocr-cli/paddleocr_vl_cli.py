#!/usr/bin/env python3
"""PaddleOCR-VL CLI — Java ``ScanOcrRunner`` 계약(§7) 전용 진입점.

Java 파이프라인이 서브프로세스로 아래처럼 호출한다::

    python3 paddleocr_vl_cli.py \
        --source-file <원본파일명> \
        --file-type <pdf|hwp|hwpx|hml> \
        --output <raw.json 출력 경로> \
        <입력 파일...>

입력 파일 규약(역할 분담: 임베디드 이미지 추출은 Java가 담당):
  * ``--file-type pdf`` : 스캔 PDF 원본 1개 (파이프라인이 페이지 렌더링)
  * 그 외(hwp/hwpx/hml) : Java Extractor가 이미 뽑아 준 임베디드 스캔 이미지 경로 N개

종료 코드 0 → ``--output`` 경로에 §4 raw JSON을 쓴다::

    {"source_file": "...", "file_type": "pdf", "is_scanned": true,
     "content": [{"type": "paragraph", "text": "..."},
                 {"type": "table", "n_rows": .., "n_cols": .., "cells": [...], "grid": [[...]]}],
     "images": [{"name": "...", "size": 1234}]}

  * 도장(seal)은 제외된 상태. 표 구조·문단은 그대로.
  * stdout/stderr는 로그로만 취급된다(사람이 읽는 상태 메시지는 stderr).
종료 코드 ≠0 → 처리 실패(로그를 stderr에 남긴다).

이 스크립트는 원본 Python 파이프라인의 ``scan/paddleocr/reader.py`` 로직을 발췌해
Java 연동에 필요한 부분만 담은 **독립 실행본**이다(``common``/``hwp``/``hwpx`` 패키지
불필요). VLM 추론 자체는 ``paddleocr``(PaddleOCR-VL)만 있으면 된다.
"""

import argparse
import json
import re
import sys
from html.parser import HTMLParser
from io import BytesIO
from pathlib import Path

# 우선 사용할 파이프라인 버전(PaddleOCR-VL-1.5)
_PIPELINE_VERSION = "v1.5"

# --- 도장 휴리스틱 (소형 + 붉은색 우세) ---
_STAMP_MAX_DIM_PX = 450   # 최대 변이 이 값(px) 이하이면 도장 후보
_STAMP_RED_DOMINANCE = 25  # R - max(G,B) 평균이 이 값 이상이면 붉은색 우세

# 본문으로 이어붙이지 않는 레이아웃 레이블(그림/차트/도장)
_IMAGE_LABELS = {"image", "chart"}
_SEAL_LABEL = "seal"

# 마크다운 이미지 문법: ![alt](path)
_MD_IMAGE_RE = re.compile(r"!\[[^\]]*\]\(\s*<?([^)>\s]+)>?\s*\)")


# ---------------------------------------------------------------------------
# 표 격자 헬퍼 (원본 common/tables.py 포팅 — 외부 의존성 제거를 위해 인라인)
# ---------------------------------------------------------------------------
def clean_grid(rows: list[list[str]]) -> list[list[str]]:
    """null 정리 후 완전히 빈 행/열을 제거한다."""
    grid: list[list[str]] = []
    for row in rows:
        cleaned = [(c or "").strip() for c in row]
        if any(cleaned):
            grid.append(cleaned)
    if not grid:
        return grid
    n_cols = len(grid[0])
    keep_cols = [j for j in range(n_cols)
                 if any(j < len(r) and r[j] for r in grid)]
    return [[(r[j] if j < len(r) else "") for j in keep_cols] for r in grid]


def grid_to_table(grid: list[list[str]]) -> dict:
    """격자를 표준 표(n_rows/n_cols/cells/grid)로 변환한다(모든 span=1)."""
    cells = []
    for i, row in enumerate(grid):
        for j, val in enumerate(row):
            cells.append({"row": i, "col": j, "row_span": 1, "col_span": 1, "text": val})
    n_cols = len(grid[0]) if grid else 0
    return {"n_rows": len(grid), "n_cols": n_cols, "cells": cells, "grid": grid}


# ---------------------------------------------------------------------------
# PaddleOCR-VL 파이프라인 (모델 의존 부분 — 지연 로딩)
# ---------------------------------------------------------------------------
_PIPELINE = None
_PIPELINE_KEY = None


def _get_pipeline(pipeline_version: str = _PIPELINE_VERSION, device: str | None = None):
    """PaddleOCR-VL 파이프라인을 (버전, device)별로 1회 생성해 재사용한다.

    설치/초기화 실패 시 한국어 RuntimeError. 첫 실행 시 VLM 모델(수 GB)을
    내려받으므로 네트워크가 필요하다.
    """
    global _PIPELINE, _PIPELINE_KEY
    key = (pipeline_version, device)
    if _PIPELINE is not None and _PIPELINE_KEY == key:
        return _PIPELINE
    try:
        from paddleocr import PaddleOCRVL
    except ImportError as exc:  # pragma: no cover - 환경 의존
        raise RuntimeError(
            "paddleocr(PaddleOCR-VL)가 설치되어 있지 않습니다. "
            "설치: pip install -r requirements.txt (paddleocr[doc-parser], paddlepaddle>=3.2.1)"
        ) from exc
    kwargs: dict = {"pipeline_version": pipeline_version}
    if device:
        kwargs["device"] = device
    try:
        _PIPELINE = PaddleOCRVL(**kwargs)
    except Exception as exc:  # pragma: no cover - 네트워크/모델 의존
        raise RuntimeError(
            "PaddleOCR-VL 초기화에 실패했습니다. 첫 실행 시 VLM 모델(수 GB) "
            "다운로드에 네트워크가 필요합니다(오프라인 환경은 모델 캐시 준비 필요). "
            f"원인: {exc}"
        ) from exc
    _PIPELINE_KEY = key
    return _PIPELINE


def _as_dict(attr):
    """result.json / result.markdown 이 속성이든 메서드든 dict로 정규화한다."""
    value = attr() if callable(attr) else attr
    return value if isinstance(value, dict) else {}


# ---------------------------------------------------------------------------
# 도장/이미지 처리
# ---------------------------------------------------------------------------
def _is_stamp(pil_image) -> bool:
    """도장 판별: 소형이면서 붉은색이 우세하면 도장으로 본다(보조 필터).

    PaddleOCR-VL의 seal 레이블이 1차 근거이고, 이 휴리스틱은 놓친 경우 대비용이다.
    """
    import numpy as np

    arr = np.asarray(pil_image.convert("RGB"))
    if arr.ndim != 3:
        return False
    h, w = arr.shape[:2]
    if max(h, w) > _STAMP_MAX_DIM_PX:
        return False
    r = arr[:, :, 0].astype(np.int32)
    g = arr[:, :, 1].astype(np.int32)
    b = arr[:, :, 2].astype(np.int32)
    dominance = float((r - np.maximum(g, b)).mean())
    return dominance >= _STAMP_RED_DOMINANCE


def _png_bytes(pil_image) -> bytes:
    """PIL 이미지를 PNG 바이트로 인코딩한다(크기 계산용)."""
    buf = BytesIO()
    pil_image.convert("RGB").save(buf, format="PNG")
    return buf.getvalue()


def _basename(path: str) -> str:
    """경로에서 파일명만 취한다(구분자 정규화)."""
    return path.replace("\\", "/").rsplit("/", 1)[-1].strip()


def _image_paths(markdown: str) -> list[str]:
    """마크다운 텍스트에서 이미지 참조 경로들을 뽑는다."""
    return _MD_IMAGE_RE.findall(markdown)


# ---------------------------------------------------------------------------
# LaTeX 서식 정리 (v1.5가 밑줄/위첨자를 LaTeX로 내놓는 것을 평문으로)
# ---------------------------------------------------------------------------
_SUPERSCRIPT = {
    "0": "⁰", "1": "¹", "2": "²", "3": "³", "4": "⁴", "5": "⁵",
    "6": "⁶", "7": "⁷", "8": "⁸", "9": "⁹", "+": "⁺", "-": "⁻", "n": "ⁿ",
}


def _to_superscript(inner: str) -> str:
    """문자열을 유니코드 위첨자로 바꾼다(매핑 없는 문자는 그대로)."""
    return "".join(_SUPERSCRIPT.get(ch, ch) for ch in inner)


def _clean_math_span(inner: str) -> str:
    """$...$ 안의 LaTeX 서식을 평문으로 편다."""
    inner = re.sub(r"\\underline\s*\{\s*\\text\s*\{([^{}]*)\}\s*\}", r"\1", inner)
    inner = re.sub(r"\\underline\s*\{([^{}]*)\}", r"\1", inner)
    inner = re.sub(r"\\text\s*\{([^{}]*)\}", r"\1", inner)
    inner = re.sub(r"\^\{([^{}]*)\}", lambda m: _to_superscript(m.group(1)), inner)
    inner = re.sub(r"\^([0-9])", lambda m: _to_superscript(m.group(1)), inner)
    inner = inner.replace("\\,", " ").replace("\\", "")  # 잔여 백슬래시 제거
    return inner.strip()


def _clean_formatting(text: str) -> str:
    """PaddleOCR-VL이 밑줄/위첨자를 LaTeX로 표기한 것을 평문으로 정리한다.

    예) '$ \\underline{\\text{신청}} $위치' → '신청위치', '면 적(m $ ^{2} $)' → '면 적(m²)'.
    """
    if not text:
        return text
    text = re.sub(r"\$(.+?)\$", lambda m: _clean_math_span(m.group(1)), text)
    text = re.sub(r"\\underline\s*\{\s*\\text\s*\{([^{}]*)\}\s*\}", r"\1", text)
    text = re.sub(r"\\underline\s*\{([^{}]*)\}", r"\1", text)
    text = re.sub(r"\\text\s*\{([^{}]*)\}", r"\1", text)
    text = re.sub(r"[ \t]+([⁰¹²³⁴-⁹⁺⁻ⁿ]+)", r"\1", text)
    text = re.sub(r"[ \t]{2,}", " ", text)
    return text


# ---------------------------------------------------------------------------
# 표(HTML/마크다운) → grid
# ---------------------------------------------------------------------------
class _TableHTMLParser(HTMLParser):
    """<table>의 행/셀을 2차원 문자열 grid로 추출한다(rowspan/colspan은 단순 무시)."""

    def __init__(self):
        super().__init__()
        self.rows: list[list[str]] = []
        self._row: list[str] | None = None
        self._cell: list[str] | None = None

    def handle_starttag(self, tag, attrs):
        """tr/td/th 시작 시 행·셀 버퍼를 연다."""
        if tag == "tr":
            self._row = []
        elif tag in ("td", "th"):
            self._cell = []

    def handle_endtag(self, tag):
        """td/th 종료 시 셀을 행에, tr 종료 시 행을 표에 확정한다."""
        if tag in ("td", "th") and self._cell is not None and self._row is not None:
            self._row.append("".join(self._cell).strip())
            self._cell = None
        elif tag == "tr" and self._row is not None:
            self.rows.append(self._row)
            self._row = None

    def handle_data(self, data):
        """열린 셀 안의 텍스트 조각을 모은다."""
        if self._cell is not None:
            self._cell.append(data)


def _html_table_to_grid(html: str) -> list[list[str]]:
    """HTML <table> 문자열을 행×열 격자로 파싱한다."""
    parser = _TableHTMLParser()
    parser.feed(html)
    return parser.rows


_MD_SEP_RE = re.compile(r"^:?-{2,}:?$")


def _md_table_to_grid(md: str) -> list[list[str]]:
    """마크다운 파이프 표(| a | b |)를 격자로 파싱한다(|---| 구분선 제외)."""
    rows: list[list[str]] = []
    for line in md.splitlines():
        line = line.strip()
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        nonempty = [c for c in cells if c]
        if nonempty and all(_MD_SEP_RE.match(c) for c in nonempty):
            continue  # |---|---| 구분선
        rows.append(cells)
    return rows


def table_content_to_table_dict(content: str) -> dict | None:
    """표 블록(HTML 또는 마크다운 표)을 표 dict로 변환한다.

    반환: {n_rows, n_cols, cells, grid} | None(빈 표).
    """
    content = (content or "").strip()
    if not content:
        return None
    low = content.lower()
    grid = _html_table_to_grid(content) if ("<table" in low or "<tr" in low) else _md_table_to_grid(content)
    grid = [[_clean_formatting(cell) for cell in row] for row in grid]
    grid = clean_grid(grid)
    if not grid:
        return None
    return grid_to_table(grid)


_MD_STRIP_RE = re.compile(r"^\s{0,3}#{1,6}\s+|[*_`]+")


def _plain_text(markdown: str) -> str:
    """마크다운 서식(제목·강조 기호)을 가볍게 제거해 평문 문단으로 만든다."""
    lines = []
    for line in markdown.splitlines():
        line = _MD_STRIP_RE.sub("", line)
        lines.append(line.strip())
    return "\n".join(line for line in lines if line).strip()


def blocks_to_raw(source_file: str, page_blocks: list[dict], images: list[dict]) -> dict:
    """구조화 블록을 §4 raw dict로 변환한다.

    text/제목 → paragraph, table → table(grid), image/chart/seal → 본문에서 제외.
    """
    content: list[dict] = []
    for page in page_blocks:
        for b in page.get("blocks", []):
            label = b.get("block_label")
            cont = (b.get("block_content") or "").strip()
            if not cont:
                continue
            if label == "table":
                table = table_content_to_table_dict(cont)
                if table and table["grid"]:
                    content.append({"type": "table", **table})
            elif label in _IMAGE_LABELS or label == _SEAL_LABEL:
                continue
            else:  # text, doc_title, paragraph_title, formula, header, footer ...
                text = _plain_text(cont)
                if text:
                    content.append({"type": "paragraph", "text": text})
    return {"source_file": source_file, "content": content, "images": images}


# ---------------------------------------------------------------------------
# 페이지 처리
# ---------------------------------------------------------------------------
def _process_page(res, stem: str, img_counter: list[int], page_no: int = 0):
    """한 페이지 결과를 (블록정보, 이미지메타 목록)으로 변환한다(도장 제외).

    반환: ({"page_index", "blocks"}, [{"name", "size"}, ...]).
    파일명에는 숫자만 넣는다(Windows 호환).
    """
    data = _as_dict(getattr(res, "json", None))
    md = _as_dict(getattr(res, "markdown", None))
    # PaddleOCR-VL의 res.json은 실제 데이터를 {"res": {...}}로 한 겹 감싼다 → 언랩.
    if isinstance(data.get("res"), dict):
        data = data["res"]
    if "markdown_texts" not in md and isinstance(md.get("res"), dict):
        md = md["res"]

    page_index = data.get("page_index")
    page_tag = page_index if isinstance(page_index, int) else page_no
    blocks = data.get("parsing_res_list") or []

    # 도장(seal) 블록의 이미지 경로를 수집해 제외 대상으로 삼는다
    seal_bases: set[str] = set()
    kept_blocks: list[dict] = []
    for b in blocks:
        if b.get("block_label") == _SEAL_LABEL:
            for p in _image_paths(b.get("block_content") or ""):
                seal_bases.add(_basename(p))
            continue
        # LaTeX 밑줄/위첨자 서식을 평문으로 정리해 저장(파싱 용이)
        kept_blocks.append({**b, "block_content": _clean_formatting(b.get("block_content") or "")})

    md_images = md.get("markdown_images") or {}
    images_meta: list[dict] = []
    for path, pil in md_images.items():
        base = _basename(path)
        # seal 레이블이거나 도장 휴리스틱에 걸리면 제외
        if base in seal_bases or _is_stamp(pil):
            continue
        img_counter[0] += 1
        name = f"{stem}_p{page_tag}_{img_counter[0]}.png"
        images_meta.append({"name": name, "size": len(_png_bytes(pil))})

    return {"page_index": page_index, "blocks": kept_blocks}, images_meta


def _run(inputs: list[str], file_type: str, source_file: str,
         pipeline_version: str, device: str | None) -> dict:
    """입력을 VLM으로 추론해 §4 raw dict를 만든다.

    pdf는 원본 PDF를, 그 외는 Java가 넘긴 이미지 경로들을 그대로 predict에 넣는다
    (임베디드 이미지 추출은 Java 담당이므로 여기서 하지 않는다).
    """
    pipeline = _get_pipeline(pipeline_version, device)
    stem = Path(source_file).stem

    if file_type == "pdf":
        output = pipeline.predict(str(inputs[0]))
    else:
        output = pipeline.predict([str(p) for p in inputs])

    page_blocks: list[dict] = []
    images_meta: list[dict] = []
    img_counter = [0]
    for page_no, res in enumerate(output):
        block_info, page_imgs = _process_page(res, stem, img_counter, page_no)
        page_blocks.append(block_info)
        images_meta.extend(page_imgs)

    raw = blocks_to_raw(source_file, page_blocks, images_meta)
    raw["file_type"] = file_type
    raw["is_scanned"] = True
    return raw


def main(argv=None) -> int:
    """인자를 파싱해 추론하고 --output에 raw JSON을 쓴다. 성공 0, 실패 1."""
    parser = argparse.ArgumentParser(
        prog="paddleocr_vl_cli",
        description="스캔 문서(PDF/이미지) → §4 raw JSON (PaddleOCR-VL, 도장 제외). "
                    "Java ScanOcrRunner 계약 전용.",
    )
    parser.add_argument("--source-file", required=True, help="원본 파일명(raw JSON의 source_file)")
    parser.add_argument("--file-type", required=True, choices=["pdf", "hwp", "hwpx", "hml"],
                        help="원본 형식. pdf면 입력은 PDF 1개, 그 외는 임베디드 이미지 경로들")
    parser.add_argument("--output", required=True, help="raw JSON을 쓸 경로")
    parser.add_argument("--device", default=None, help="추론 장치(gpu:0/cpu 등). 기본 자동 감지")
    parser.add_argument("--pipeline-version", default=_PIPELINE_VERSION,
                        help=f"PaddleOCR-VL 파이프라인 버전(기본 {_PIPELINE_VERSION})")
    parser.add_argument("inputs", nargs="+", help="입력 파일 경로들")
    args = parser.parse_args(argv)

    missing = [p for p in args.inputs if not Path(p).exists()]
    if missing:
        print(f"[실패] 입력 파일을 찾을 수 없습니다: {missing}", file=sys.stderr)
        return 1

    try:
        raw = _run(args.inputs, args.file_type, args.source_file,
                   args.pipeline_version, args.device)
    except (RuntimeError, OSError) as exc:
        print(f"[실패] {args.source_file}: {exc}", file=sys.stderr)
        return 1

    out = Path(args.output)
    if out.parent:
        out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(raw, ensure_ascii=False, indent=2), encoding="utf-8")

    n_para = sum(1 for c in raw["content"] if c.get("type") == "paragraph")
    n_tbl = sum(1 for c in raw["content"] if c.get("type") == "table")
    print(f"[완료] {args.source_file}: 문단 {n_para} / 표 {n_tbl} / 이미지 {len(raw['images'])}개 -> {out}",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main() or 0)
