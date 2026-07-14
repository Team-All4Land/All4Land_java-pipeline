package com.onnara.extract.detect;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
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

    private static final int MIN_TEXT_CHARS = 50;

    @Override
    public boolean isScanned(Path file) {
        try {
            HWPFile hwpFile = HWPReader.fromFile(file.toString());
            int textLen = 0;
            for (Section section : hwpFile.getBodyText().getSectionList()) {
                for (Paragraph paragraph : section.getParagraphs()) {
                    String text = paragraph.getNormalString();
                    if (text != null) {
                        textLen += text.trim().length();
                    }
                    if (textLen >= MIN_TEXT_CHARS) {
                        return false;
                    }
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
}
