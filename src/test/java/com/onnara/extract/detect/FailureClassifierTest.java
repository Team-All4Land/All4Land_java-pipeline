package com.onnara.extract.detect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실패 갈래 분류({@link FailureClassifier}) 단위 테스트. */
class FailureClassifierTest {

    /** 배치에서 가장 흔할 것으로 본 갈래 — 헤더만 보면 라이브러리 오류 메시지 없이도 확정된다. */
    @Test
    void detectsLegacyHwp3ByFileHeader(@TempDir Path dir) throws IOException {
        Path file = write(dir, "구버전.hwp", "HWP Document File V3.00 ");

        FailureClassifier.Result result = FailureClassifier.classify(file, hwpFailure(file));

        assertEquals(FailureKind.HWP3_LEGACY, result.kind());
    }

    /** 컨테이너가 정상 CFB면 "구버전"도 "다른 형식"도 아니고 내부 파싱 문제다 — 대응이 다르다. */
    @Test
    void separatesInnerParseFailureFromBrokenContainer(@TempDir Path dir) throws IOException {
        Path file = writeBytes(dir, "정상컨테이너.hwp",
                new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                        (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0x00, 0x00});

        FailureClassifier.Result result = FailureClassifier.classify(file, hwpFailure(file));

        assertEquals(FailureKind.DOCUMENT_PARSE, result.kind());
    }

    /** .hwp도 CFB도 아니면 개명된 다른 형식이다. */
    @Test
    void flagsHwpThatIsNotACompoundFile(@TempDir Path dir) throws IOException {
        Path file = write(dir, "사실은텍스트.hwp", "그냥 텍스트입니다");

        FailureClassifier.Result result = FailureClassifier.classify(file, hwpFailure(file));

        assertEquals(FailureKind.NOT_COMPOUND_FILE, result.kind());
    }

    /** .hwpx가 ZIP이 아니면 개명 파일이고, 무엇이었는지까지 상세에 남겨야 대응할 수 있다. */
    @Test
    void detectsHwpThatWasRenamedToHwpx(@TempDir Path dir) throws IOException {
        Path file = writeBytes(dir, "개명.hwpx",
                new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                        (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});

        FailureClassifier.Result result = FailureClassifier.classify(file,
                new UncheckedIOException("HWPX 스캔 판별 실패: " + file,
                        new ZipException("error in opening zip file")));

        assertEquals(FailureKind.NOT_ZIP, result.kind());
        assertTrue(result.detail().contains("HWP 5.0"), result.detail());
    }

    /** ZIP은 맞는데 항목이 암호화됐다면 손상이 아니라 암호다 — 원본을 다시 받아야 한다. */
    @Test
    void detectsEncryptedZipEntry(@TempDir Path dir) throws IOException {
        Path file = writeBytes(dir, "암호.hwpx", new byte[]{'P', 'K', 0x03, 0x04, 0x14, 0x00});

        FailureClassifier.Result result = FailureClassifier.classify(file,
                new UncheckedIOException("HWPX 스캔 판별 실패: " + file,
                        new ZipException("encrypted ZIP entry not supported")));

        assertEquals(FailureKind.ENCRYPTED, result.kind());
    }

    /** %PDF 서명이 없으면 구조 손상이 아니라 애초에 PDF가 아니다 — 상세에 남긴다. */
    @Test
    void notesWhenPdfLacksSignature(@TempDir Path dir) throws IOException {
        Path file = write(dir, "가짜.pdf", "not a pdf at all");

        FailureClassifier.Result result = FailureClassifier.classify(file,
                new UncheckedIOException("PDF 스캔 판별 실패: " + file, new IOException("bad xref")));

        assertEquals(FailureKind.PDF_LOAD, result.kind());
        assertTrue(result.detail().contains("%PDF 서명이 없습니다"), result.detail());
    }

    /** 0바이트 파일은 형식 판정 이전에 걸러 내야 근거 없는 갈래로 새지 않는다. */
    @Test
    void detectsEmptyFile(@TempDir Path dir) throws IOException {
        Path file = writeBytes(dir, "빈파일.hwp", new byte[0]);

        FailureClassifier.Result result = FailureClassifier.classify(file, hwpFailure(file));

        assertEquals(FailureKind.EMPTY_FILE, result.kind());
    }

    /** 미등록 확장자는 파싱 실패와 섞이면 안 된다(대응이 "형식 지원 추가"로 완전히 다르다). */
    @Test
    void detectsUnsupportedExtension(@TempDir Path dir) throws IOException {
        Path file = write(dir, "문서.txt", "내용");

        FailureClassifier.Result result = FailureClassifier.classify(file,
                new IllegalArgumentException("지원하지 않는 확장자입니다: txt (" + file + ")"));

        assertEquals(FailureKind.UNSUPPORTED_EXT, result.kind());
    }

    /** 경로가 없으면 형식 문제가 아니라 접근 문제다. */
    @Test
    void detectsMissingFile(@TempDir Path dir) {
        Path missing = dir.resolve("없는파일.hwp");

        FailureClassifier.Result result = FailureClassifier.classify(missing,
                new UncheckedIOException("HWP 스캔 판별 실패: " + missing,
                        new java.nio.file.NoSuchFileException(missing.toString())));

        assertEquals(FailureKind.FILE_ACCESS, result.kind());
    }

    /** OOM은 파일 문제가 아니라 실행 환경 문제다 — 힙을 늘리라는 안내로 이어져야 한다. */
    @Test
    void detectsOutOfMemory(@TempDir Path dir) throws IOException {
        Path file = write(dir, "거대.hml", "<HWPML/>");

        FailureClassifier.Result result =
                FailureClassifier.classify(file, new OutOfMemoryError("Java heap space"));

        assertEquals(FailureKind.OUT_OF_MEMORY, result.kind());
    }

    /** 모든 갈래는 사유 문장에 원인 체인을 담아야 한다 — 갈래만으로는 개별 건을 못 쫓는다. */
    @Test
    void detailAlwaysCarriesTheCauseChain(@TempDir Path dir) throws IOException {
        Path file = write(dir, "깨진.hml", "<HWPML");

        FailureClassifier.Result result = FailureClassifier.classify(file,
                new UncheckedIOException("HML 스캔 판별 실패: " + file,
                        new IOException("XML 문서 구조가 올바르지 않습니다")));

        assertTrue(result.detail().contains("XML 문서 구조가 올바르지 않습니다"), result.detail());
    }

    /** 판별기가 실제로 던지는 모양의 예외(래퍼 + 원인). */
    private static Throwable hwpFailure(Path file) {
        return new UncheckedIOException("HWP 스캔 판별 실패: " + file,
                new IOException(new IllegalStateException("hwplib 내부 오류")));
    }

    /** 텍스트 파일을 만든다. */
    private static Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** 바이너리 파일을 만든다. */
    private static Path writeBytes(Path dir, String name, byte[] content) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, content);
        return file;
    }
}
