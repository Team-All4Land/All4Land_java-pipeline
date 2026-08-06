package com.onnara.extract.detect;

import com.onnara.extract.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 매직바이트 기반 형식 판별({@link DocFormat})의 단위 테스트. */
class DocFormatTest {

    /** 지정한 바이트로 파일을 만든다. */
    private static Path writeBytes(Path dir, String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    /** 지정한 텍스트로 파일을 만든다. */
    private static Path write(Path dir, String name, String text) throws IOException {
        return writeBytes(dir, name, text.getBytes(StandardCharsets.UTF_8));
    }

    /** OLE 복합문서(CFB) 서명은 HWP 5.0이다. */
    @Test
    void detectsHwp5ByCompoundFileSignature(@TempDir Path dir) throws IOException {
        Path file = writeBytes(dir, "문서.hwp",
                new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                        (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});

        assertEquals(DocFormat.HWP5, DocFormat.of(file));
        assertEquals("hwp", DocFormat.routingKey(file));
    }

    /** 한글 3.0은 자체 서명 문자열로 갈린다 — 확장자는 HWP 5.0과 똑같이 .hwp다. */
    @Test
    void detectsHwp3BySignatureString(@TempDir Path dir) throws IOException {
        Path file = write(dir, "구버전.hwp", "HWP Document File V3.00 뒤에 본문");

        assertEquals(DocFormat.HWP3, DocFormat.of(file));
        assertEquals("hwp3", DocFormat.routingKey(file));
    }

    /** ZIP 서명은 HWPX로 본다. */
    @Test
    void detectsHwpxByZipSignature(@TempDir Path dir) throws IOException {
        Path file = writeBytes(dir, "문서.hwpx", new byte[]{'P', 'K', 0x03, 0x04, 0x14, 0x00});

        assertEquals(DocFormat.HWPX, DocFormat.of(file));
    }

    /** %PDF 서명은 PDF다. */
    @Test
    void detectsPdfBySignature(@TempDir Path dir) throws IOException {
        Path file = write(dir, "문서.pdf", "%PDF-1.7\n나머지");

        assertEquals(DocFormat.PDF, DocFormat.of(file));
    }

    /** HML은 매직바이트가 없어 XML 선언과 루트 태그로 본다. */
    @Test
    void detectsHmlByRootTag(@TempDir Path dir) throws IOException {
        Path file = write(dir, "문서.hml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<HWPML Version=\"2.8\">");

        assertEquals(DocFormat.HML, DocFormat.of(file));
    }

    /**
     * <b>이 판별의 존재 이유</b> — 확장자가 .hwp인데 내용이 HWPX면 HWPX로 라우팅돼야 한다.
     * 확장자만 보면 hwplib으로 가 "OLE 복합문서가 아니다"로 실패하던 파일이다.
     */
    @Test
    void routesRenamedHwpxByContentNotExtension() {
        Path file = TestFixtures.sample("개명 hwpx - 공유수면 점사용 변경허가(SNNC).hwp");

        assertEquals(DocFormat.HWPX, DocFormat.of(file));
        assertEquals("hwpx", DocFormat.routingKey(file));
    }

    /** 실제 한글 3.0 픽스처도 서명으로 갈린다. */
    @Test
    void routesRealHwp3Sample() {
        Path file = TestFixtures.sample("한글3.0 공유수면 고시(동해시).hwp");

        assertEquals(DocFormat.HWP3, DocFormat.of(file));
        assertEquals("hwp3", DocFormat.routingKey(file));
    }

    /**
     * 아는 서명이 없으면 UNKNOWN이고, 라우팅은 확장자로 폴백한다 —
     * 새 판별 규칙이 기존 동작을 좁히지 않게 하는 안전장치다.
     */
    @Test
    void fallsBackToExtensionWhenSignatureIsUnknown(@TempDir Path dir) throws IOException {
        Path file = write(dir, "정체불명.pdf", "서명이라 할 만한 게 없는 내용");

        assertEquals(DocFormat.UNKNOWN, DocFormat.of(file));
        assertEquals("pdf", DocFormat.routingKey(file));
    }

    /** 읽을 수 없는 파일은 UNKNOWN — 판별이 예외를 던져 배치를 멈추면 안 된다. */
    @Test
    void returnsUnknownForMissingFile(@TempDir Path dir) {
        assertEquals(DocFormat.UNKNOWN, DocFormat.of(dir.resolve("없는파일.hwp")));
    }
}
