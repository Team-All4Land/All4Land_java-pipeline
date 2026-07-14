package com.onnara.extract.detect;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * 스캔본 PDF 판별 — Python pdf/detect.py 포팅.
 *
 * <p>텍스트 레이어가 있는 디지털 PDF는 PdfBoxExtractor로, 이미지뿐인 스캔 PDF는
 * OCR 경로로 라우팅하기 위해 문서 단위로 스캔 여부를 판정한다.
 *
 * <p>스캐너가 심어 놓은 OCR 텍스트 레이어가 있으면 '디지털'로 본다
 * (그 경우 기존 파이프라인이 텍스트를 그대로 읽을 수 있으므로 정상 동작).
 */
public class PdfScanDetector implements ScanDetector {

    /**
     * 페이지에서 추출된 텍스트가 이 글자 수 이상이면 '텍스트 레이어 있음'으로 본다.
     * (머리글/쪽번호만 있는 스캔 페이지가 오탐되지 않도록 여유를 둔다)
     */
    private static final int MIN_TEXT_CHARS = 25;

    /** 텍스트 페이지 비율이 이 값 미만이면 문서를 스캔본으로 판정한다. */
    private static final double TEXT_PAGE_RATIO = 0.5;

    /**
     * PDF가 스캔본(이미지 전용)인지 판정한다.
     *
     * <p>각 페이지의 텍스트 레이어 유무를 세어, 텍스트가 있는 페이지 비율이
     * TEXT_PAGE_RATIO 미만이면 true(스캔본)를 반환한다. 페이지가 없으면 false.
     */
    @Override
    public boolean isScanned(Path file) {
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            int nPages = doc.getNumberOfPages();
            if (nPages == 0) {
                return false;
            }
            PDFTextStripper stripper = new PDFTextStripper();
            int texty = 0;
            for (int p = 1; p <= nPages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                if (stripper.getText(doc).trim().length() >= MIN_TEXT_CHARS) {
                    texty++;
                }
            }
            return (double) texty / nPages < TEXT_PAGE_RATIO;
        } catch (IOException e) {
            throw new UncheckedIOException("PDF 스캔 판별 실패: " + file, e);
        }
    }
}