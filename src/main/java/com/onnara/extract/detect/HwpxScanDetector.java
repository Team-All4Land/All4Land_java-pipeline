package com.onnara.extract.detect;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 스캔본 HWPX 판별 — ZIP 내 {@code Contents/section*.xml}의 텍스트량과
 * {@code BinData/} 임베디드 이미지 개수를 비교한다.
 *
 * <p>hwpxlib 없이 ZipFile + StAX만으로 가볍게 판정한다(배치 1차 분류이므로
 * 전체 문서 모델을 만들 필요가 없음).
 */
public class HwpxScanDetector implements ScanDetector {

    /** 본문 텍스트가 이 글자 수 미만이면서 BinData 이미지가 있으면 스캔본으로 본다. */
    private static final int MIN_TEXT_CHARS = 50;

    /**
     * ZIP 엔트리를 훑어 {@code Contents/section*.xml}의 텍스트 길이와
     * {@code BinData/} 이미지 개수를 집계하고, 텍스트가 임계치 미만이면서
     * 이미지가 있으면 스캔본(true)으로 판정한다.
     */
    @Override
    public boolean isScanned(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            int textLen = 0;
            int imageCount = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("Contents/section") && name.endsWith(".xml")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        textLen += textLength(in);
                    }
                } else if (name.startsWith("BinData/") && !entry.isDirectory()) {
                    imageCount++;
                }
            }
            return textLen < MIN_TEXT_CHARS && imageCount > 0;
        } catch (IOException e) {
            throw new UncheckedIOException("HWPX 스캔 판별 실패: " + file, e);
        }
    }

    /** {@code <hp:t>} 요소(로컬명 "t") 안의 문자 데이터 총 길이를 센다. */
    private static int textLength(InputStream in) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        int len = 0;
        boolean inText = false;
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        inText = "t".equals(reader.getLocalName());
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        inText = false;
                    } else if (event == XMLStreamConstants.CHARACTERS && inText) {
                        len += reader.getText().trim().length();
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new IOException("HWPX 섹션 XML 파싱 실패", e);
        }
        return len;
    }
}
