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
 * 스캔본 HML 판별 — 본문 {@code <CHAR>} 텍스트량이 {@link #MIN_TEXT_CHARS}자
 * 미만이면서 {@code <BINDATA>} base64 임베디드 이미지가 있으면 스캔본으로 본다.
 *
 * <p>이미지의 개수·크기는 판정 근거가 아니다(§ScanDetector) — 사진이 지면 대부분을
 * 차지해도 본문 텍스트가 있으면 네이티브다. HML은 표 셀·글상자·캡션 문단도 모두
 * {@code <CHAR>}로 들어오므로, 태그 위치를 가리지 않고 세는 것만으로 본문이
 * 빠짐없이 집계된다.
 *
 * <p>StAX를 {@link InputStream}으로 생성해 UTF-8 BOM을 파서가 자동 처리하게 한다.
 */
public class HmlScanDetector implements ScanDetector {

    /**
     * StAX로 본문 {@code <CHAR>} 텍스트 길이와 {@code <BINDATA>} base64 길이를
     * 집계하고, 텍스트가 임계치 미만이면서 BINDATA가 있으면 스캔본(true)으로 판정한다.
     *
     * <p>머리말/꼬리말({@code <HEADER>} / {@code <FOOTER>}) 안의 글자는 본문으로 세지 않는다.
     * 진짜 스캔본에도 머리글·쪽번호는 남아 있어, 본문으로 세면 임계치가 한 글자인 판정에서
     * 스캔본이 곧바로 네이티브로 뒤집힌다.
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
            int headerFooterDepth = 0;

            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    // 대상 태그일 때만 플래그를 바꾼다 — CHAR 안의 중첩 요소가
                    // 플래그를 끊어 본문이 과소집계되면 스캔본으로 오판된다
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String tag = reader.getLocalName();
                        if (isHeaderOrFooter(tag)) {
                            headerFooterDepth++;
                        } else if ("CHAR".equals(tag)) {
                            inChar = true;
                        } else if ("BINDATA".equals(tag)) {
                            inBinData = true;
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String tag = reader.getLocalName();
                        if (isHeaderOrFooter(tag)) {
                            headerFooterDepth--;
                        } else if ("CHAR".equals(tag)) {
                            inChar = false;
                        } else if ("BINDATA".equals(tag)) {
                            inBinData = false;
                        }
                    } else if (event == XMLStreamConstants.CHARACTERS) {
                        if (inChar && headerFooterDepth == 0) {
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

    /** 머리말/꼬리말 요소인지 — 그 안의 글자는 본문으로 세지 않는다. */
    private static boolean isHeaderOrFooter(String localName) {
        return "HEADER".equals(localName) || "FOOTER".equals(localName);
    }
}
