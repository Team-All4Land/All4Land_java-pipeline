package com.onnara.extract.scan;

import com.onnara.extract.common.ImageProbe;
import com.onnara.extract.common.model.RawContent;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 문서에 삽입된 이미지를 PaddleOCR-VL로 읽어 raw JSON에 합친다.
 *
 * <p>고시류에는 본문 대부분이 사진인 문서가 흔하다(붙임 현장사진, 위치도, 표 캡처).
 * 이런 문서는 스캔본이 아니어서 네이티브 추출기가 정상 동작하지만, 정작 정보가
 * 들어 있는 이미지는 {@code images[]}에 이름·크기만 남고 내용은 어디에도 안 남았다.
 * 이 클래스가 그 이미지들을 OCR해 두 곳에 넣는다.
 *
 * <ul>
 *   <li>{@code content[]} — 읽어 낸 문단·표를 본문 뒤에 잇고 {@code source_image}로 출처를 표시.
 *       (hwplib 등은 이미지가 몇 번째 문단에 붙어 있었는지 복원할 수 없어 본문 사이에
 *       끼워 넣지 못한다. 위치를 지어내는 대신 뒤에 붙이고 출처만 정확히 남긴다.)</li>
 *   <li>{@code images[].ocr_text} — 이미지별 평문. 계약에 있으나 비어 있던 필드를 채운다.</li>
 * </ul>
 *
 * <p>도장·로고·서명처럼 작은 이미지는 후보에서 뺀다 — 본문 정보가 없는데
 * VLM 추론 시간만 들고, 25만 건 배치에서는 그 비용이 그대로 배치 시간이 된다.
 */
public final class ImageOcrEnricher {

    /** 표를 평문 ocr_text로 접을 때 셀 구분자. */
    private static final String CELL_SEPARATOR = " | ";

    /** OCR 서브프로세스 실행기. */
    private final ScanOcrRunner runner;

    /** 후보 선별 기준(최소 긴 변)과 사용 여부. */
    private final ScanOcrConfig config;

    /** 실행기와 설정을 주입해 생성한다. */
    public ImageOcrEnricher(ScanOcrRunner runner, ScanOcrConfig config) {
        this.runner = runner;
        this.config = config;
    }

    /** 이미지 OCR이 켜져 있고 실행 스크립트도 있는지 — 파이프라인이 시작 전에 한 번 확인한다. */
    public boolean isUsable() {
        return config.imageOcrEnabled() && runner.isAvailable();
    }

    /**
     * OCR 후보 이미지를 고른다 — 긴 변이 {@code ocr.images.min-dimension} 이상인 것만.
     *
     * <p>크기를 못 재는 이미지({@link ImageProbe#maxDimension} 가 -1)는 후보에 남긴다.
     * 판단 불가를 '작다'로 취급하면 읽을 수 있었을 본문을 조용히 버리게 된다.
     */
    public List<Path> candidates(List<Path> images) {
        List<Path> out = new ArrayList<>();
        for (Path image : images) {
            int max = ImageProbe.maxDimension(image);
            if (max < 0 || max >= config.imageMinDimension()) {
                out.add(image);
            }
        }
        return out;
    }

    /**
     * 후보 이미지를 OCR해 {@code raw}에 병합한다.
     *
     * @param fileType raw JSON의 형식 식별자(hwp/hwpx/hml/pdf) — 입력은 항상 이미지 목록으로 넘긴다
     * @return 병합된 content 항목 수. 0이면 후보가 없었거나 읽어 낸 내용이 없다는 뜻
     * @throws IOException OCR 서브프로세스 실패 — 스캔본처럼 이미지가 유일한 본문 출처인
     *                     경우에만 치명적이므로, 호출부가 잡아서 등급을 정한다
     */
    public int enrich(RawDocument raw, List<Path> images, String fileType) throws IOException {
        List<Path> candidates = candidates(images);
        if (candidates.isEmpty()) {
            return 0;
        }

        RawDocument ocr = runner.parseImages(candidates, raw.getSourceFile(), fileType);
        Map<String, StringBuilder> textByImage = new LinkedHashMap<>();
        List<RawContent> merged = new ArrayList<>();
        for (RawContent item : ocr.getContent()) {
            merged.add(item);
            String origin = item.getSourceImage();
            if (origin != null) {
                appendText(textByImage.computeIfAbsent(origin, k -> new StringBuilder()), item);
            }
        }
        if (merged.isEmpty()) {
            return 0;
        }

        raw.getContent().addAll(merged);
        for (RawImage image : raw.getImages()) {
            StringBuilder text = textByImage.get(image.getName());
            if (text != null && !text.isEmpty()) {
                image.setOcrText(text.toString());
            }
        }
        return merged.size();
    }

    /** content 항목의 텍스트를 이미지별 평문 버퍼에 줄 단위로 잇는다(표는 셀을 ' | '로 접는다). */
    private static void appendText(StringBuilder buffer, RawContent item) {
        String text = plainText(item);
        if (text.isEmpty()) {
            return;
        }
        if (!buffer.isEmpty()) {
            buffer.append('\n');
        }
        buffer.append(text);
    }

    /** 문단은 텍스트 그대로, 표는 행마다 셀을 이어 붙인 평문으로 만든다. */
    private static String plainText(RawContent item) {
        if (item instanceof RawParagraph paragraph) {
            return paragraph.getText() == null ? "" : paragraph.getText().trim();
        }
        if (item instanceof RawTable table && table.getGrid() != null) {
            StringBuilder out = new StringBuilder();
            for (List<String> row : table.getGrid()) {
                String line = String.join(CELL_SEPARATOR, row).trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(line);
            }
            return out.toString();
        }
        return "";
    }
}
