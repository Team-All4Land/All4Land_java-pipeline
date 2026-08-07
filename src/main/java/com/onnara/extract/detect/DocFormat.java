package com.onnara.extract.detect;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 파일 앞부분의 매직바이트로 판정한 <b>실제 문서 형식</b> — 확장자가 아니라 내용이 근거다.
 *
 * <p>고시류 코퍼스에는 확장자와 내용이 어긋난 파일이 흔하다. 실제로 HWPX 컨테이너
 * (ZIP, {@code mimetype=application/hwp+zip})를 {@code .hwp}로 저장한 파일이 들어오는데,
 * 확장자만 보고 hwplib으로 보내면 "OLE 복합문서가 아니다"로 실패한다 — <b>읽을 수 있는
 * 문서를 형식 판정 하나 때문에 버리는 것이다.</b> 판별·추출 라우팅을 이 클래스에 태워
 * 그런 파일을 살린다.
 *
 * <p>{@link #of}는 앞 {@value #PROBE_BYTES}바이트만 읽으므로 수만 건 배치에서도 비용이
 * 사실상 없다. 판정할 수 없으면 {@link #UNKNOWN}을 돌려주고, {@link #routingKey}가
 * 확장자로 폴백한다 — 새 판정 규칙이 기존 동작을 좁히는 일은 없다.
 */
public enum DocFormat {

    /** 한글 5.0 이상(.hwp) — OLE 복합문서(CFB) 컨테이너. */
    HWP5("hwp"),

    /** 한글 3.0 이하(.hwp) — 자체 바이너리 포맷. hwplib이 읽지 못해 전용 엔진이 필요하다. */
    HWP3("hwp3"),

    /** HWPX — ZIP 컨테이너. */
    HWPX("hwpx"),

    /** HML — 매직바이트가 없는 텍스트 XML. */
    HML("hml"),

    /** PDF. */
    PDF("pdf"),

    /** 판정하지 못했다 — 확장자로 폴백해야 한다는 뜻이지, 실패가 아니다. */
    UNKNOWN("");

    /** 매직바이트 판별에 읽을 바이트 수 — HML의 XML 선언·루트 태그까지 덮는다. */
    static final int PROBE_BYTES = 512;

    /** OLE 복합문서(CFB) 서명 — HWP 5.0 컨테이너. */
    private static final byte[] CFB = {
            (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};

    /** 한글 3.0 이하의 파일 서명. */
    private static final byte[] HWP3_SIGNATURE = "HWP Document File".getBytes(StandardCharsets.US_ASCII);

    /** ZIP 로컬 파일 헤더 서명 — HWPX 컨테이너. */
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04};

    /** PDF 서명. */
    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F'};

    /** 레지스트리 키 — 확장자 문자열과 같은 공간을 쓴다(UNKNOWN만 빈 문자열). */
    private final String key;

    /** 레지스트리 키를 지정해 형식을 만든다. */
    DocFormat(String key) {
        this.key = key;
    }

    /**
     * 판별기·추출기 레지스트리 키. 기존 확장자 키와 같은 문자열을 쓰므로
     * {@link DetectorRegistry}·{@link com.onnara.extract.engine.ExtractorRegistry}의
     * 등록 구조를 바꾸지 않고 그대로 얹힌다.
     */
    public String key() {
        return key;
    }

    /**
     * 파일 내용으로 형식을 판정한다. 읽지 못하거나 아는 서명이 없으면 {@link #UNKNOWN}.
     *
     * <p>ZIP이면 HWPX로 본다. ZIP이지만 HWPX가 아닌 파일은 hwpxlib이 실패시키고
     * {@link FailureClassifier}가 {@code ZIP_CORRUPT}/{@code XML_PARSE}로 분류하므로,
     * 여기서 {@code mimetype} 항목까지 열어 볼 이유가 없다(전량 배치에서 ZIP을 여는 비용만 는다).
     */
    public static DocFormat of(Path file) {
        return ofHead(probe(file));
    }

    /** 이미 읽어 둔 앞부분 바이트로 형식을 판정한다({@link FailureClassifier}가 재사용). */
    static DocFormat ofHead(byte[] head) {
        if (startsWith(head, CFB)) {
            return HWP5;
        }
        if (startsWith(head, HWP3_SIGNATURE)) {
            return HWP3;
        }
        if (startsWith(head, ZIP)) {
            return HWPX;
        }
        if (startsWith(head, PDF_SIGNATURE)) {
            return PDF;
        }
        if (looksLikeHml(head)) {
            return HML;
        }
        return UNKNOWN;
    }

    /**
     * 라우팅에 쓸 형식 키 — 내용으로 판정하되, 판정하지 못하면 확장자로 폴백한다.
     *
     * <p>폴백이 있어야 판정 규칙이 기존 동작을 좁히지 않는다. 예를 들어 BOM이나 주석이
     * 길게 붙어 앞 {@value #PROBE_BYTES}바이트에서 {@code HWPML}을 못 본 HML 파일도
     * 지금까지처럼 확장자로 처리된다.
     */
    public static String routingKey(Path file) {
        DocFormat format = of(file);
        return format == UNKNOWN ? DetectorRegistry.extensionOf(file) : format.key;
    }

    /**
     * HML인지 — 매직바이트가 없는 텍스트 XML이라 루트 태그 이름으로 본다.
     *
     * <p>{@code <?xml ...?>} 뒤에 {@code HWPML}이 나오면 HML이다. UTF-8 BOM과 인코딩 선언은
     * 앞부분을 Latin-1로 훑는 것만으로 지나칠 수 있다(태그 이름은 어차피 ASCII다).
     */
    private static boolean looksLikeHml(byte[] head) {
        if (head.length == 0) {
            return false;
        }
        String text = new String(head, StandardCharsets.ISO_8859_1);
        return text.contains("<?xml") && text.contains("HWPML");
    }

    /** 파일 앞부분을 읽는다. 읽지 못하면 빈 배열(그 자체가 판정 근거가 되지는 않는다). */
    static byte[] probe(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(PROBE_BYTES);
        } catch (IOException | RuntimeException e) {
            return new byte[0];
        }
    }

    /** {@code data}가 {@code signature}로 시작하는지. */
    static boolean startsWith(byte[] data, byte[] signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (data[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
