package com.onnara.extract.detect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HWPX/HML 판별기가 본문과 머리말·꼬리말을 가려 세는지 검증한다.
 *
 * <p>두 판별기는 본문 태그({@code <hp:t>} / {@code <CHAR>})를 문서 어느 위치에서든 세기
 * 때문에 머리말·꼬리말 글자까지 본문으로 잡혔다. 임계치가 한 글자인 판정에서는 그게 곧
 * "머리글이 있는 스캔본은 전부 네이티브"가 된다 — 진짜 스캔본에도 머리글·쪽번호는 남는다.
 *
 * <p>{@code samples/}에는 머리말이 있는 파일이 없어, 판정에 필요한 구조만 담은 최소 문서를
 * 그 자리에서 만든다(두 판별기 모두 전체 문서 모델 없이 태그만 훑으므로 이걸로 충분하다).
 */
class XmlScanDetectorTest {

    @TempDir
    Path tempDir;

    /** {@code Contents/section0.xml} + {@code BinData/} 이미지 1개를 담은 최소 HWPX를 만든다. */
    private Path hwpx(String name, String sectionBody) throws IOException {
        Path file = tempDir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("Contents/section0.xml"));
            write(zip, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<hs:sec xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\""
                    + " xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\">"
                    + sectionBody + "</hs:sec>");
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("BinData/image1.png"));
            zip.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
            zip.closeEntry();
        }
        return file;
    }

    /** {@code <BINDATA>} 1개를 담은 최소 HML을 만든다. */
    private Path hml(String name, String sectionBody) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<HWPML><BODY><SECTION>" + sectionBody + "</SECTION></BODY>"
                + "<BINDATALIST><BINDATA>iVBORw0KGgo=</BINDATA></BINDATALIST></HWPML>",
                StandardCharsets.UTF_8);
        return file;
    }

    private static void write(OutputStream out, String xml) throws IOException {
        out.write(xml.getBytes(StandardCharsets.UTF_8));
    }

    /** HWPX: 머리말 글자는 본문이 아니다 — 본문이 없으면 스캔본. */
    @Test
    void hwpxHeaderTextIsNotCountedAsBody() throws Exception {
        Path file = hwpx("머리말만.hwpx",
                "<hp:header><hp:subList><hp:p><hp:run>"
                        + "<hp:t>포항시 고시 제2026-27호</hp:t>"
                        + "</hp:run></hp:p></hp:subList></hp:header>");

        assertTrue(new HwpxScanDetector().isScanned(file),
                "머리말만 있는 문서는 본문이 없는 것으로 봐야 함");
    }

    /** HWPX: 머리말 밖 본문 글자는 한 글자라도 네이티브로 만든다. */
    @Test
    void hwpxBodyTextOutsideHeaderIsCounted() throws Exception {
        Path file = hwpx("본문있음.hwpx",
                "<hp:header><hp:subList><hp:p><hp:run>"
                        + "<hp:t>포항시 고시 제2026-27호</hp:t>"
                        + "</hp:run></hp:p></hp:subList></hp:header>"
                        + "<hp:p><hp:run><hp:t>붙임</hp:t></hp:run></hp:p>");

        assertFalse(new HwpxScanDetector().isScanned(file),
                "머리말 밖 본문이 있으면 네이티브");
    }

    /** HML: 머리말 글자는 본문이 아니다 — 본문이 없으면 스캔본. */
    @Test
    void hmlHeaderTextIsNotCountedAsBody() throws Exception {
        Path file = hml("머리말만.hml",
                "<HEADER><PARALIST><P><TEXT><CHAR>포항시 고시 제2026-27호</CHAR></TEXT></P></PARALIST></HEADER>");

        assertTrue(new HmlScanDetector().isScanned(file),
                "머리말만 있는 문서는 본문이 없는 것으로 봐야 함");
    }

    /** HML: 머리말 밖 본문 글자는 한 글자라도 네이티브로 만든다. */
    @Test
    void hmlBodyTextOutsideHeaderIsCounted() throws Exception {
        Path file = hml("본문있음.hml",
                "<HEADER><PARALIST><P><TEXT><CHAR>포항시 고시 제2026-27호</CHAR></TEXT></P></PARALIST></HEADER>"
                        + "<P><TEXT><CHAR>붙임</CHAR></TEXT></P>");

        assertFalse(new HmlScanDetector().isScanned(file),
                "머리말 밖 본문이 있으면 네이티브");
    }
}
