package com.onnara.extract.common;

import java.util.Locale;
import java.util.Set;

/**
 * 임베디드 이미지 바이트의 확장자를 판별한다 — 저장 파일명의 단일 출처.
 *
 * <p>1순위는 매직바이트다. HWP/HWPX/HML이 선언하는 확장자는 틀릴 수 있어(개명·잘못 저장된 서식)
 * 바이트가 말하는 것을 우선한다.
 *
 * <p>다만 매직바이트만으로는 부족하다. 고시류에는 WMF·EMF·OLE 개체처럼 서명이 제각각이거나
 * 아예 없는 형식이 일상적으로 들어가는데, 예전에는 JPEG/PNG/BMP/GIF 넷만 알고 나머지를 전부
 * {@code .bin}으로 떨어뜨렸다. 그래서 <b>스니핑이 실패했을 때만</b> 컨테이너가 선언한 확장자를
 * 2순위로 쓴다({@link #extensionFor(byte[], String)}). 둘 다 실패하면 {@code .bin}을 유지하되
 * 호출부가 경고를 남길 수 있도록 {@link #isUnknown}과 {@link #magicOf}를 제공한다.
 */
public final class ImageFormats {

    /** 어느 서명에도 걸리지 않은 바이트에 붙는 확장자. */
    public static final String UNKNOWN = "bin";

    /**
     * 컨테이너 선언값으로 받아들일 확장자 — 아무 문자열이나 파일명에 붙이지 않기 위한 화이트리스트.
     * 확장자는 파일 경로가 되므로 검증 없이 쓰면 경로 조작에 노출된다.
     */
    private static final Set<String> ALLOWED_HINTS = Set.of(
            "jpg", "jpeg", "png", "bmp", "gif", "tif", "tiff", "webp",
            "wmf", "emf", "svg", "ico", "pcx", "ole");

    /** 인스턴스화 방지 — 정적 판별 함수만 제공하는 유틸리티 클래스. */
    private ImageFormats() {
    }

    /**
     * 매직바이트만으로 확장자(점 제외)를 판별한다. 어느 것도 아니면 {@value #UNKNOWN}.
     *
     * <p>컨테이너가 확장자를 알고 있다면 {@link #extensionFor(byte[], String)}를 쓰는 편이 낫다.
     */
    public static String extensionFor(byte[] d) {
        return extensionFor(d, null);
    }

    /**
     * 매직바이트로 판별하고, 실패하면 컨테이너가 선언한 확장자로 넘어간다.
     *
     * @param d    이미지 바이트
     * @param hint 컨테이너가 선언한 확장자 또는 파일명·MIME 타입(예: {@code "BIN0001.jpg"},
     *             {@code "BinData/image1.bmp"}, {@code "image/png"}, {@code "wmf"}). 없으면 null.
     */
    public static String extensionFor(byte[] d, String hint) {
        String sniffed = sniff(d);
        if (!UNKNOWN.equals(sniffed)) {
            return sniffed;
        }
        String fromHint = normalizeHint(hint);
        return fromHint != null ? fromHint : UNKNOWN;
    }

    /** 판별에 실패한 확장자인지 — 호출부가 경고를 남길지 결정할 때 쓴다. */
    public static boolean isUnknown(String extension) {
        return UNKNOWN.equals(extension);
    }

    /** 앞 8바이트를 대문자 16진수로 — 알 수 없는 형식을 로그로 진단할 수 있게 한다. */
    public static String magicOf(byte[] d) {
        if (d == null || d.length == 0) {
            return "(비어 있음)";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(8, d.length); i++) {
            out.append(String.format("%02X", d[i]));
        }
        return out.toString();
    }

    /** 매직바이트 판별 본체. */
    private static String sniff(byte[] d) {
        if (d == null || d.length < 2) {
            return UNKNOWN;
        }
        // JPEG: FF D8 로 시작하고 다음이 마커(FF)다. 마커 검사를 생략하면 FF D8 로 시작하는
        // 임의의 바이트열까지 jpg가 되고, 강제하면 일부 정상 JPEG을 놓친다 — 길이로 절충한다.
        if (u(d[0]) == 0xFF && u(d[1]) == 0xD8) {
            return "jpg";
        }
        if (startsWith(d, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "png";
        }
        if (startsWith(d, 'G', 'I', 'F', '8')) {
            return "gif";
        }
        // BMP: "BM" 두 글자만으로는 근거가 약해 헤더의 파일 크기 필드까지 확인한다
        if (d.length >= 6 && d[0] == 'B' && d[1] == 'M' && le32(d, 2) > 0) {
            return "bmp";
        }
        if (startsWith(d, 'I', 'I', 0x2A, 0x00) || startsWith(d, 'M', 'M', 0x00, 0x2A)) {
            return "tif";
        }
        if (startsWith(d, 'R', 'I', 'F', 'F') && d.length >= 12
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "webp";
        }
        // WMF: placeable 헤더(D7CDC69A) 또는 표준 헤더(0100 0900)
        if (startsWith(d, 0xD7, 0xCD, 0xC6, 0x9A) || startsWith(d, 0x01, 0x00, 0x09, 0x00)) {
            return "wmf";
        }
        // EMF: 레코드 타입 1 + 오프셋 40의 " EMF" 서명
        if (startsWith(d, 0x01, 0x00, 0x00, 0x00) && d.length >= 44
                && d[40] == ' ' && d[41] == 'E' && d[42] == 'M' && d[43] == 'F') {
            return "emf";
        }
        if (startsWith(d, 0x00, 0x00, 0x01, 0x00)) {
            return "ico";
        }
        // OLE 복합문서 — 이미지가 아니라 삽입 개체(수식·차트 등)다. 이미지 확장자를 붙이면
        // 뷰어가 깨진 그림으로 오해하므로 형식을 그대로 밝힌다.
        if (startsWith(d, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
            return "ole";
        }
        if (looksLikeSvg(d)) {
            return "svg";
        }
        return UNKNOWN;
    }

    /** 앞부분에 {@code <svg} 또는 XML 선언이 있는지 — SVG는 서명이 없어 내용으로 본다. */
    private static boolean looksLikeSvg(byte[] d) {
        int limit = Math.min(d.length, 256);
        String head = new String(d, 0, limit, java.nio.charset.StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        return head.contains("<svg") || (head.startsWith("<?xml") && head.contains("svg"));
    }

    /**
     * 힌트에서 확장자를 뽑아 정규화한다. 파일명·경로·MIME 타입 어느 형태로 와도 받는다.
     * 화이트리스트에 없으면 null(= 힌트를 쓰지 않음).
     */
    private static String normalizeHint(String hint) {
        if (hint == null || hint.isBlank()) {
            return null;
        }
        String value = hint.trim().toLowerCase(Locale.ROOT);
        int slash = value.lastIndexOf('/');
        if (value.startsWith("image/") || value.startsWith("application/")) {
            value = value.substring(slash + 1);       // image/png → png
        } else {
            value = value.substring(Math.max(value.lastIndexOf('.'), slash) + 1);
        }
        int semi = value.indexOf(';');
        if (semi >= 0) {
            value = value.substring(0, semi);         // image/jpeg;charset=… 방어
        }
        value = value.trim();
        if ("x-emf".equals(value) || "emf".equals(value)) {
            value = "emf";
        } else if ("x-wmf".equals(value) || "wmf".equals(value)) {
            value = "wmf";
        } else if ("svg+xml".equals(value)) {
            value = "svg";
        }
        return ALLOWED_HINTS.contains(value) ? value : null;
    }

    /** {@code d}가 주어진 바이트 서명으로 시작하는지(값은 0~255로 준다). */
    private static boolean startsWith(byte[] d, int... signature) {
        if (d.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (u(d[i]) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /** 부호 없는 바이트 값. */
    private static int u(byte b) {
        return b & 0xFF;
    }

    /** 리틀엔디언 32비트 정수. */
    private static long le32(byte[] d, int offset) {
        return (long) u(d[offset]) | ((long) u(d[offset + 1]) << 8)
                | ((long) u(d[offset + 2]) << 16) | ((long) u(d[offset + 3]) << 24);
    }
}
