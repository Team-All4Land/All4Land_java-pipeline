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

    private static final int MIN_TEXT_CHARS = 50;

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
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String tag = reader.getLocalName();
                        inChar = "CHAR".equals(tag);
                        inBinData = "BINDATA".equals(tag);
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        inChar = false;
                        inBinData = false;
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
