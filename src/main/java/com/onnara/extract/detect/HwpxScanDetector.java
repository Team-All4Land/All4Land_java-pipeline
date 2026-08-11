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
 * 스캔본 HWPX 판별 — ZIP 내 {@code Contents/section*.xml}의 텍스트량이
 * {@link #MIN_TEXT_CHARS}자 미만이면서 {@code BinData/} 임베디드 이미지가 있으면
 * 스캔본으로 본다.
 *
 * <p>이미지의 개수·크기는 판정 근거가 아니다(§ScanDetector) — 사진이 지면 대부분을
 * 차지해도 본문 텍스트가 있으면 네이티브다. HWPX는 글상자·캡션 문단도 모두
 * {@code <hp:t>}로 들어오므로, 태그 위치를 가리지 않고 세는 것만으로 본문이
 * 빠짐없이 집계된다.
 *
 * <p>hwpxlib 없이 ZipFile + StAX만으로 가볍게 판정한다(배치 1차 분류이므로
 * 전체 문서 모델을 만들 필요가 없음).
 */
public class HwpxScanDetector implements ScanDetector {

    /**
     * ZIP 엔트리를 훑어 {@code Contents/section*.xml}의 텍스트 길이와
     * {@code BinData/} 이미지 개수를 집계하고, 텍스트가 임계치 미만이면서
     * 이미지가 있으면 스캔본(true)으로 판정한다.
     */
    @Override
    public boolean isScanned(Path file) {
        try {
            // 잠긴 문서는 먼저 걸러 낸다 — 본문 XML이 암호문이라 StAX가
            // "Invalid byte 1 of 1-byte UTF-8 sequence"로 죽고, 그러면 손상으로 오진된다
            EncryptionProbe.requireUnlocked(file);
        } catch (IOException e) {
            throw new UncheckedIOException("HWPX 스캔 판별 실패: " + file, e);
        }
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

    /**
     * 본문 {@code <hp:t>} 요소(로컬명 "t") 안의 문자 데이터 총 길이를 센다.
     *
     * <p>머리말/꼬리말({@code <hp:header>} / {@code <hp:footer>}) 안의 글자는 제외한다.
     * 진짜 스캔본에도 머리글·쪽번호는 남아 있어, 본문으로 세면 임계치가 한 글자인 판정에서
     * 스캔본이 곧바로 네이티브로 뒤집힌다. 중첩될 일은 없지만 깊이로 세어, 안쪽에 또
     * 머리말이 나와도 빠져나오는 지점을 놓치지 않는다.
     */
    private static int textLength(InputStream in) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        int len = 0;
        boolean inText = false;
        int headerFooterDepth = 0;
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    // "t" 태그일 때만 플래그를 바꾼다 — t 안의 중첩 요소가 플래그를
                    // 끊어 본문이 과소집계되면 스캔본으로 오판된다
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String tag = reader.getLocalName();
                        if (isHeaderOrFooter(tag)) {
                            headerFooterDepth++;
                        } else if ("t".equals(tag)) {
                            inText = true;
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String tag = reader.getLocalName();
                        if (isHeaderOrFooter(tag)) {
                            headerFooterDepth--;
                        } else if ("t".equals(tag)) {
                            inText = false;
                        }
                    } else if (event == XMLStreamConstants.CHARACTERS && inText && headerFooterDepth == 0) {
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

    /** 머리말/꼬리말 컨트롤 요소인지 — 그 안의 글자는 본문으로 세지 않는다. */
    private static boolean isHeaderOrFooter(String localName) {
        return "header".equals(localName) || "footer".equals(localName);
    }
}
