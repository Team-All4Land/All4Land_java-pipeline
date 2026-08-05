package com.onnara.extract.detect;

import com.onnara.extract.common.Errors;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 실패한 파일 하나를 {@link FailureKind}로 분류한다 — 수백 건의 실패를 대응 단위로 묶기 위한 계층.
 *
 * <p>근거를 둘 쓴다.
 *
 * <ol>
 *   <li><b>파일 앞부분의 매직바이트</b> — 예외 메시지보다 신뢰할 수 있다. 라이브러리는 "읽지 못했다"까지만
 *       말해 주지만, 헤더를 직접 보면 "한글 3.0이라 애초에 대상이 아니다"와 "정상 컨테이너인데 안이 깨졌다"가
 *       갈린다. 앞 {@value #PROBE_BYTES}바이트만 읽으므로 전량 배치에서도 비용이 없다.</li>
 *   <li><b>원인 체인의 맨 끝 예외</b>({@link Errors#rootCause}) — 래퍼는 전부 같은 문장이라
 *       분류에 쓸 수 없다.</li>
 * </ol>
 *
 * <p>분류에 실패하면 {@link FailureKind#OTHER}로 남긴다. 그 건수가 늘면 규칙을 보강하라는 신호이지,
 * 억지로 다른 갈래에 밀어 넣어 통계를 왜곡할 이유가 없다.
 */
public final class FailureClassifier {

    /** 매직바이트 판별에 읽을 바이트 수 — 가장 긴 서명(EMF의 오프셋 40 " EMF")을 덮는다. */
    private static final int PROBE_BYTES = 64;

    /** OLE 복합문서(CFB) 서명 — HWP 5.0 컨테이너. */
    private static final byte[] CFB = {
            (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};

    /** 한글 3.0 이하의 파일 서명. */
    private static final byte[] HWP3 = "HWP Document File".getBytes(StandardCharsets.US_ASCII);

    /** ZIP 로컬 파일 헤더 서명 — HWPX 컨테이너. */
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04};

    /** PDF 서명. */
    private static final byte[] PDF = {'%', 'P', 'D', 'F'};

    /** 판별 결과 — 갈래와, 그 갈래로 본 근거를 담은 상세 문장. */
    public record Result(FailureKind kind, String detail) {
    }

    /** 인스턴스화 방지 — 정적 분류 함수만 제공하는 유틸리티 클래스. */
    private FailureClassifier() {
    }

    /**
     * 실패한 파일과 그때의 예외로 갈래를 판정한다.
     *
     * @param file  실패한 파일(헤더를 읽어 보기 위해 필요하다)
     * @param error 판별·추출이 던진 예외(래핑된 상태 그대로 넘겨도 된다)
     */
    public static Result classify(Path file, Throwable error) {
        Throwable root = Errors.rootCause(error);
        String message = messageOf(error);
        String ext = DetectorRegistry.extensionOf(file);
        byte[] head = probe(file);
        long size = sizeOf(file);

        // 1) 파일에 도달조차 못한 경우 — 헤더도 예외 메시지도 볼 필요가 없다
        if (root instanceof OutOfMemoryError || root instanceof StackOverflowError) {
            return result(FailureKind.OUT_OF_MEMORY, error);
        }
        if (root instanceof java.nio.file.NoSuchFileException
                || root instanceof java.io.FileNotFoundException
                || root instanceof java.nio.file.AccessDeniedException) {
            return result(FailureKind.FILE_ACCESS, error);
        }
        if (size == 0) {
            return result(FailureKind.EMPTY_FILE, error);
        }
        if (root instanceof IllegalArgumentException && message.contains("지원하지 않는 확장자")) {
            return result(FailureKind.UNSUPPORTED_EXT, error);
        }

        // 2) 암호는 형식을 가리지 않는다 — 컨테이너 판정보다 먼저 본다
        if (mentionsEncryption(message) || isPasswordException(root)) {
            return result(FailureKind.ENCRYPTED, error);
        }

        // 3) 확장자별 컨테이너 판정 — 매직바이트가 예외 메시지보다 확실하다
        Result byContainer = switch (ext) {
            case "hwp" -> classifyHwp(head, error);
            case "hwpx" -> classifyHwpx(head, root, error);
            case "pdf" -> classifyPdf(head, error);
            case "hml" -> null;
            default -> null;
        };
        if (byContainer != null) {
            return byContainer;
        }

        // 4) 남은 것은 예외 타입으로 가른다
        if (root instanceof javax.xml.stream.XMLStreamException
                || root instanceof org.xml.sax.SAXException
                || message.contains("XML")) {
            return result(FailureKind.XML_PARSE, error);
        }
        if (root instanceof java.util.zip.ZipException) {
            return result(FailureKind.ZIP_CORRUPT, error);
        }
        if (root instanceof IOException) {
            return result(FailureKind.IO, error);
        }
        return result(FailureKind.OTHER, error);
    }

    /** .hwp — 컨테이너가 CFB인지, 한글 3.0인지, 아예 다른 형식인지로 가른다. */
    private static Result classifyHwp(byte[] head, Throwable error) {
        if (startsWith(head, HWP3)) {
            return result(FailureKind.HWP3_LEGACY, error);
        }
        if (!startsWith(head, CFB)) {
            return result(FailureKind.NOT_COMPOUND_FILE, error);
        }
        // 컨테이너는 정상 — 안에서 깨진 것이므로 컨테이너 문제와 구분해 남긴다
        return result(FailureKind.DOCUMENT_PARSE, error);
    }

    /** .hwpx — ZIP이 아니면 개명 파일, ZIP이면 압축 해제나 본문 XML에서 깨진 것이다. */
    private static Result classifyHwpx(byte[] head, Throwable root, Throwable error) {
        if (!startsWith(head, ZIP)) {
            // .hwp(CFB)나 한글 3.0 파일을 .hwpx로 개명한 사례가 흔해, 무엇이었는지까지 남긴다
            String was = startsWith(head, CFB) ? " (실제 내용은 HWP 5.0 컨테이너입니다)"
                    : startsWith(head, HWP3) ? " (실제 내용은 한글 3.0 파일입니다)" : "";
            return new Result(FailureKind.NOT_ZIP, Errors.describe(error) + was);
        }
        if (root instanceof java.util.zip.ZipException) {
            return result(FailureKind.ZIP_CORRUPT, error);
        }
        if (root instanceof javax.xml.stream.XMLStreamException) {
            return result(FailureKind.XML_PARSE, error);
        }
        return result(FailureKind.DOCUMENT_PARSE, error);
    }

    /**
     * .pdf — 열지 못한 결과는 같지만 원인은 둘로 갈린다. %PDF 서명이 없으면 애초에 PDF가 아니고
     * (개명 파일), 있으면 xref 등 구조가 깨진 것이다. 대응이 다르므로 상세에 남긴다.
     */
    private static Result classifyPdf(byte[] head, Throwable error) {
        String note = startsWith(head, PDF) ? "" : " (%PDF 서명이 없습니다 — PDF가 아닌 파일을 .pdf로 개명했을 수 있습니다)";
        return new Result(FailureKind.PDF_LOAD, Errors.describe(error) + note);
    }

    /** 갈래와 원인 체인 서술을 묶는다 — 상세 문장은 항상 원인까지 담는다. */
    private static Result result(FailureKind kind, Throwable error) {
        return new Result(kind, Errors.describe(error));
    }

    /** PDFBox의 암호 예외인지 — 클래스 이름으로 본다(패키지 의존을 늘리지 않기 위해). */
    private static boolean isPasswordException(Throwable root) {
        return root != null && root.getClass().getSimpleName().contains("InvalidPassword");
    }

    /** 메시지가 암호·유통 문서를 가리키는지. */
    private static boolean mentionsEncryption(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("encrypt") || lower.contains("password")
                || lower.contains("암호") || lower.contains("배포용");
    }

    /** 예외와 그 원인 체인 전체의 메시지 — 어느 계층이 단서를 갖고 있을지 모른다. */
    private static String messageOf(Throwable error) {
        return error == null ? "" : Errors.describe(error);
    }

    /** 파일 앞부분을 읽는다. 읽지 못하면 빈 배열(그 자체가 분류 근거가 되지는 않는다). */
    private static byte[] probe(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(PROBE_BYTES);
        } catch (IOException | RuntimeException e) {
            return new byte[0];
        }
    }

    /** 파일 크기 — 알 수 없으면 -1(0바이트 판정과 구분한다). */
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** {@code data}가 {@code signature}로 시작하는지. */
    private static boolean startsWith(byte[] data, byte[] signature) {
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
