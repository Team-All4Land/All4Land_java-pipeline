package com.onnara.extract.common;

/**
 * 이미지 바이트의 매직바이트로 확장자를 판별한다.
 *
 * <p>HWP/HWPX/HML의 BinData는 확장자 정보가 신뢰할 수 없거나 없으므로
 * 저장 파일명은 항상 이 판별 결과를 쓴다.
 */
public final class ImageFormats {

    /** 인스턴스화 방지 — 정적 판별 함수만 제공하는 유틸리티 클래스. */
    private ImageFormats() {
    }

    /**
     * 이미지 바이트 앞부분의 매직바이트를 검사해 확장자(점 제외)를 반환한다.
     * JPEG/PNG/BMP/GIF를 인식하고, 어느 것도 아니면 {@code "bin"}.
     */
    public static String extensionFor(byte[] d) {
        if (d == null) {
            return "bin";
        }
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "png";
        }
        if (d.length >= 2 && d[0] == 'B' && d[1] == 'M') {
            return "bmp";
        }
        if (d.length >= 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') {
            return "gif";
        }
        return "bin";
    }
}
