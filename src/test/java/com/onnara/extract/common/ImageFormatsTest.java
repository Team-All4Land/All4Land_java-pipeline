package com.onnara.extract.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 이미지 확장자 판별({@link ImageFormats}) 단위 테스트. */
class ImageFormatsTest {

    /** 기존에도 알던 네 형식은 그대로 판별해야 한다(회귀 방지). */
    @Test
    void recognizesCommonRasterFormats() {
        assertEquals("jpg", ImageFormats.extensionFor(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10)));
        assertEquals("png", ImageFormats.extensionFor(
                bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)));
        assertEquals("gif", ImageFormats.extensionFor(bytes('G', 'I', 'F', '8', '9', 'a')));
        assertEquals("bmp", ImageFormats.extensionFor(bytes('B', 'M', 0x46, 0x00, 0x00, 0x00)));
    }

    /**
     * 고시류에 흔한 나머지 형식 — 이들이 전부 .bin으로 떨어지던 것이 문제의 본체였다.
     */
    @Test
    void recognizesFormatsThatUsedToFallThroughToBin() {
        assertEquals("tif", ImageFormats.extensionFor(bytes('I', 'I', 0x2A, 0x00)));
        assertEquals("tif", ImageFormats.extensionFor(bytes('M', 'M', 0x00, 0x2A)));
        assertEquals("wmf", ImageFormats.extensionFor(bytes(0xD7, 0xCD, 0xC6, 0x9A, 0x00, 0x00)));
        assertEquals("wmf", ImageFormats.extensionFor(bytes(0x01, 0x00, 0x09, 0x00, 0x00, 0x03)));
        assertEquals("ico", ImageFormats.extensionFor(bytes(0x00, 0x00, 0x01, 0x00, 0x01, 0x00)));
    }

    /** WEBP는 RIFF 컨테이너라 8바이트 뒤의 형식 표시까지 봐야 한다. */
    @Test
    void recognizesWebpThroughRiffContainer() {
        byte[] data = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, data, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, data, 8, 4);

        assertEquals("webp", ImageFormats.extensionFor(data));
    }

    /** EMF 서명은 오프셋 40에 있다 — 앞 4바이트만 보면 다른 형식과 구분되지 않는다. */
    @Test
    void recognizesEmfBySignatureAtOffset40() {
        byte[] data = new byte[48];
        data[0] = 0x01;
        System.arraycopy(" EMF".getBytes(StandardCharsets.US_ASCII), 0, data, 40, 4);

        assertEquals("emf", ImageFormats.extensionFor(data));
    }

    /** OLE 삽입 개체는 이미지가 아니다 — 이미지 확장자를 붙이면 깨진 그림으로 오해된다. */
    @Test
    void labelsOleCompoundObjectsAsOleNotImage() {
        assertEquals("ole", ImageFormats.extensionFor(
                bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)));
    }

    /** SVG는 서명이 없어 내용으로 본다. */
    @Test
    void recognizesSvgByContent() {
        byte[] data = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>"
                .getBytes(StandardCharsets.UTF_8);

        assertEquals("svg", ImageFormats.extensionFor(data));
    }

    /** 매직바이트가 안 맞을 때만 컨테이너가 선언한 확장자를 쓴다 — 파일명·경로·MIME 어느 형태든. */
    @Test
    void fallsBackToContainerHintWhenSniffFails() {
        byte[] unknown = bytes(0x11, 0x22, 0x33, 0x44, 0x55, 0x66);

        assertEquals("jpg", ImageFormats.extensionFor(unknown, "BIN0001.jpg"));
        assertEquals("bmp", ImageFormats.extensionFor(unknown, "BinData/image1.bmp"));
        assertEquals("png", ImageFormats.extensionFor(unknown, "image/png"));
        assertEquals("wmf", ImageFormats.extensionFor(unknown, "wmf"));
    }

    /** 바이트가 말하는 것이 힌트를 이긴다 — 개명·잘못 저장된 서식이 실재한다. */
    @Test
    void magicBytesWinOverContainerHint() {
        byte[] png = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);

        assertEquals("png", ImageFormats.extensionFor(png, "BIN0001.jpg"));
    }

    /** 확장자는 파일 경로가 되므로 화이트리스트에 없는 힌트는 쓰지 않는다. */
    @Test
    void rejectsHintsOutsideTheWhitelist() {
        byte[] unknown = bytes(0x11, 0x22, 0x33, 0x44);

        assertEquals("bin", ImageFormats.extensionFor(unknown, "../../etc/passwd"));
        assertEquals("bin", ImageFormats.extensionFor(unknown, "exe"));
        assertEquals("bin", ImageFormats.extensionFor(unknown, ""));
        assertEquals("bin", ImageFormats.extensionFor(unknown, null));
    }

    /** 판별 실패는 여전히 .bin이되, 호출부가 경고를 남길 수 있게 드러나야 한다. */
    @Test
    void exposesUnknownSoCallersCanWarn() {
        byte[] unknown = bytes(0x11, 0x22, 0x33, 0x44);

        assertTrue(ImageFormats.isUnknown(ImageFormats.extensionFor(unknown)));
        assertFalse(ImageFormats.isUnknown("png"));
        assertEquals("11223344", ImageFormats.magicOf(unknown));
        assertEquals("(비어 있음)", ImageFormats.magicOf(new byte[0]));
    }

    /** null·너무 짧은 바이트에도 예외 없이 .bin을 돌려줘야 한다. */
    @Test
    void handlesNullAndTinyInput() {
        assertEquals("bin", ImageFormats.extensionFor(null));
        assertEquals("bin", ImageFormats.extensionFor(new byte[0]));
        assertEquals("bin", ImageFormats.extensionFor(bytes('B')));
    }

    /** 0~255 값으로 바이트 배열을 만든다. */
    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
