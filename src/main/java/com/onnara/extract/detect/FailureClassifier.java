package com.onnara.extract.detect;

import com.onnara.extract.common.Errors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 실패한 파일 하나를 {@link FailureKind}로 분류한다 — 수백 건의 실패를 대응 단위로 묶기 위한 계층.
 *
 * <p>근거를 둘 쓴다.
 *
 * <ol>
 *   <li><b>파일 앞부분의 매직바이트</b>({@link DocFormat}) — 예외 메시지보다 신뢰할 수 있다.
 *       라이브러리는 "읽지 못했다"까지만 말해 주지만, 헤더를 직접 보면 "한글 3.0이다"와
 *       "정상 컨테이너인데 안이 깨졌다"가 갈린다.</li>
 *   <li><b>원인 체인의 맨 끝 예외</b>({@link Errors#rootCause}) — 래퍼는 전부 같은 문장이라
 *       분류에 쓸 수 없다.</li>
 * </ol>
 *
 * <p>서명 상수와 헤더 읽기는 {@link DocFormat}에 있다 — 라우팅과 실패 분류가 같은 근거를 써야
 * "라우팅은 HWPX로 보냈는데 실패 갈래는 HWP 얘기를 한다" 같은 어긋남이 생기지 않는다.
 *
 * <p>분류에 실패하면 {@link FailureKind#OTHER}로 남긴다. 그 건수가 늘면 규칙을 보강하라는 신호이지,
 * 억지로 다른 갈래에 밀어 넣어 통계를 왜곡할 이유가 없다.
 */
public final class FailureClassifier {

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
        byte[] head = DocFormat.probe(file);
        DocFormat format = DocFormat.ofHead(head);
        // 라우팅과 같은 근거로 갈래를 고른다 — 판정 불가일 때만 확장자로 폴백한다
        String ext = format == DocFormat.UNKNOWN ? DetectorRegistry.extensionOf(file) : format.key();
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

        // 2) 잠금은 형식을 가리지 않는다 — 컨테이너 판정보다 먼저 본다.
        //    근거는 예외 메시지가 아니라 컨테이너다: 라이브러리는 암호 얘기를 하지 않고
        //    "문단이 아니다"·"UTF-8이 아니다"라고만 하므로, 그것만 보면 배포용 문서가
        //    통째로 손상 갈래에 묻힌다.
        Result byLock = switch (EncryptionProbe.probe(file)) {
            case DISTRIBUTION -> result(FailureKind.DISTRIBUTION_LOCKED, error);
            case PASSWORD -> result(FailureKind.ENCRYPTED, error);
            case NONE -> null;
        };
        if (byLock != null) {
            return byLock;
        }
        // 프로브가 열지 못한 경우와 PDF(컨테이너에 암호 비트가 없다)를 위한 폴백
        if (message.contains("배포용")) {
            return result(FailureKind.DISTRIBUTION_LOCKED, error);
        }
        if (mentionsEncryption(message) || isPasswordException(root)) {
            return result(FailureKind.ENCRYPTED, error);
        }

        // 3) 컨테이너 판정 — 매직바이트가 예외 메시지보다 확실하다
        Result byContainer = switch (format) {
            // 컨테이너는 정상 — 안에서 깨진 것이므로 컨테이너 문제와 구분해 남긴다
            case HWP5 -> result(FailureKind.DOCUMENT_PARSE, error);
            case HWP3 -> result(FailureKind.HWP3_LEGACY, error);
            case HWPX -> classifyZipContainer(root, error);
            case PDF -> result(FailureKind.PDF_LOAD, error);
            case HML -> null;
            case UNKNOWN -> classifyUnrecognized(ext, error);
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

    /** ZIP 컨테이너(HWPX) — 압축 해제에서 깨졌는지, 본문 XML에서 깨졌는지로 가른다. */
    private static Result classifyZipContainer(Throwable root, Throwable error) {
        if (root instanceof java.util.zip.ZipException) {
            return result(FailureKind.ZIP_CORRUPT, error);
        }
        if (root instanceof javax.xml.stream.XMLStreamException) {
            return result(FailureKind.XML_PARSE, error);
        }
        return result(FailureKind.DOCUMENT_PARSE, error);
    }

    /**
     * 아는 서명이 하나도 없는 파일 — 확장자가 말하는 형식이 아닌 무언가다.
     *
     * <p>내용 기반 라우팅({@link DocFormat})을 거친 뒤에도 여기 오는 건은 "개명 파일"이
     * 아니라 <b>헤더가 손상됐거나 우리가 모르는 형식</b>이다. 개명은 이제 라우팅이 흡수한다.
     */
    private static Result classifyUnrecognized(String ext, Throwable error) {
        String note = " (아는 형식 서명이 없습니다 — 헤더가 손상됐거나 지원 대상이 아닌 형식입니다)";
        return switch (ext) {
            case "hwp" -> new Result(FailureKind.NOT_COMPOUND_FILE, Errors.describe(error) + note);
            case "hwpx" -> new Result(FailureKind.NOT_ZIP, Errors.describe(error) + note);
            case "pdf" -> new Result(FailureKind.PDF_LOAD, Errors.describe(error) + note);
            default -> null;
        };
    }

    /** 갈래와 원인 체인 서술을 묶는다 — 상세 문장은 항상 원인까지 담는다. */
    private static Result result(FailureKind kind, Throwable error) {
        return new Result(kind, Errors.describe(error));
    }

    /** PDFBox의 암호 예외인지 — 클래스 이름으로 본다(패키지 의존을 늘리지 않기 위해). */
    private static boolean isPasswordException(Throwable root) {
        return root != null && root.getClass().getSimpleName().contains("InvalidPassword");
    }

    /** 메시지가 암호를 가리키는지 — 배포용은 {@link EncryptionProbe}와 위의 폴백이 먼저 잡는다. */
    private static boolean mentionsEncryption(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("encrypt") || lower.contains("password") || lower.contains("암호");
    }

    /** 예외와 그 원인 체인 전체의 메시지 — 어느 계층이 단서를 갖고 있을지 모른다. */
    private static String messageOf(Throwable error) {
        return error == null ? "" : Errors.describe(error);
    }

    /** 파일 크기 — 알 수 없으면 -1(0바이트 판정과 구분한다). */
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }
}
