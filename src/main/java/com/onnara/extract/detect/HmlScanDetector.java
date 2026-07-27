package com.onnara.extract.detect;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 스캔본 HML 판별 — 본문 {@code <CHAR>} 텍스트량과 {@code <BINDATA>}
 * base64 임베디드 이미지 유무를 비교한다.
 *
 * <p>StAX를 {@link InputStream}으로 생성해 UTF-8 BOM을 파서가 자동 처리하게 한다.
 */
public class HmlScanDetector implements ScanDetector {

    /** 본문 텍스트가 이 글자 수 미만이면서 BINDATA가 있으면 스캔본으로 본다. */
    private static final int MIN_TEXT_CHARS = 50;

    /**
     * StAX로 본문 {@code <CHAR>} 텍스트 길이와 {@code <BINDATA>} base64 길이를
     * 집계하고, 텍스트가 임계치 미만이면서 BINDATA가 있으면 스캔본(true)으로 판정한다.
     */
    @Override
    public boolean isScanned(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

            int textLen = 0;
            long binDataLen = 0;
            boolean inChar = false;
            boolean inBinData = false;

            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    // 대상 태그일 때만 플래그를 바꾼다 — CHAR 안의 중첩 요소가
                    // 플래그를 끊어 본문이 과소집계되면 스캔본으로 오판된다
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String tag = reader.getLocalName();
                        if ("CHAR".equals(tag)) {
                            inChar = true;
                        } else if ("BINDATA".equals(tag)) {
                            inBinData = true;
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String tag = reader.getLocalName();
                        if ("CHAR".equals(tag)) {
                            inChar = false;
                        } else if ("BINDATA".equals(tag)) {
                            inBinData = false;
                        }
                    } else if (event == XMLStreamConstants.CHARACTERS) {
                        if (inChar) {
                            textLen += reader.getText().trim().length();
                        } else if (inBinData) {
                            binDataLen += reader.getText().trim().length();
                        }
                    }
                }
            } finally {
                reader.close();
            }
            return textLen < MIN_TEXT_CHARS && binDataLen > 0;
        } catch (IOException | XMLStreamException e) {
            throw new UncheckedIOException("HML 스캔 판별 실패: " + file,
                    e instanceof IOException io ? io : new IOException(e));
        }
    }
}
