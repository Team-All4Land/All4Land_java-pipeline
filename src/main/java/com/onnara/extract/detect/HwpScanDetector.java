package com.onnara.extract.detect;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.reader.HWPReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * 스캔본 HWP 판별 — hwplib로 네이티브 본문 텍스트량과 임베디드 이미지 유무를 비교한다.
 *
 * <p>본문 텍스트가 거의 없고(&lt;{@value #MIN_TEXT_CHARS}자) 임베디드 이미지가
 * 1개 이상이면 스캔본(전체 페이지가 이미지)으로 판정한다.
 */
public class HwpScanDetector implements ScanDetector {

    /** 이 글자 수 이상 본문 텍스트가 나오면 네이티브 문서로 확정(조기 반환)한다. */
    private static final int MIN_TEXT_CHARS = 50;

    /**
     * 본문 문단·표 셀 텍스트를 누적하다 임계치를 넘으면 네이티브(false)로 조기 판정하고,
     * 텍스트가 임계치 미만이면서 임베디드 BinData 이미지가 있으면 스캔본(true)으로 본다.
     */
    @Override
    public boolean isScanned(Path file) {
        try {
            HWPFile hwpFile = HWPReader.fromFile(file.toString());
            int textLen = 0;
            for (Section section : hwpFile.getBodyText().getSectionList()) {
                textLen = accumulateText(section.getParagraphs(), textLen);
                if (textLen >= MIN_TEXT_CHARS) {
                    return false;
                }
            }
            int imageCount = hwpFile.getBinData() == null
                    ? 0
                    : hwpFile.getBinData().getEmbeddedBinaryDataList().size();
            return imageCount > 0;
        } catch (Exception e) {
            throw new UncheckedIOException("HWP 스캔 판별 실패: " + file,
                    e instanceof IOException io ? io : new IOException(e));
        }
    }

    /**
     * 문단 텍스트에 표 셀 텍스트까지 더해 누적한다(임계치 도달 시 조기 반환).
     *
     * <p>hwplib에서 표 셀 문단은 {@code section.getParagraphs()}에 포함되지 않는다.
     * 고시류 문서는 본문 대부분이 표 안에 있어, 표 셀을 세지 않으면 도장·로고
     * 이미지(BinData)만 가진 네이티브 문서가 스캔본으로 오판돼 OCR 경로로 새어 나간다
     * — HwplibExtractor가 표 셀을 읽는 것과 같은 경로로 세어야 한다.
     */
    private static int accumulateText(Paragraph[] paragraphs, int textLen) throws Exception {
        for (Paragraph paragraph : paragraphs) {
            String text = paragraph.getNormalString();
            if (text != null) {
                textLen += text.trim().length();
            }
            if (textLen >= MIN_TEXT_CHARS) {
                return textLen;
            }
            if (paragraph.getControlList() == null) {
                continue;
            }
            for (Control control : paragraph.getControlList()) {
                if (control.getType() != ControlType.Table) {
                    continue;
                }
                for (Row row : ((ControlTable) control).getRowList()) {
                    for (Cell cell : row.getCellList()) {
                        String cellText = cell.getParagraphList().getNormalString();
                        if (cellText != null) {
                            textLen += cellText.trim().length();
                        }
                        if (textLen >= MIN_TEXT_CHARS) {
                            return textLen;
                        }
                    }
                }
            }
        }
        return textLen;
    }
}
