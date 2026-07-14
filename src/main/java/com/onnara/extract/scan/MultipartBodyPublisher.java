package com.onnara.extract.scan;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code multipart/form-data} 본문 작성기.
 *
 * <p>JDK {@link java.net.http.HttpClient}에는 multipart 작성 기능이 없으므로
 * 최소 구현을 둔다. 파트 헤더는 UTF-8로 인코딩해 한글 파일명을 지원한다.
 */
public final class MultipartBodyPublisher {

    private final String boundary = "----extract" + UUID.randomUUID();
    private final List<byte[]> parts = new ArrayList<>();

    public MultipartBodyPublisher addField(String name, String value) {
        parts.add(text(
                "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                        + value + "\r\n"));
        return this;
    }

    public MultipartBodyPublisher addFile(String name, String filename, byte[] data, String contentType) {
        parts.add(text(
                "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                        + "Content-Type: " + contentType + "\r\n\r\n"));
        parts.add(data);
        parts.add(text("\r\n"));
        return this;
    }

    /** {@code multipart/form-data; boundary=...} — 요청의 Content-Type 헤더 값. */
    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    public HttpRequest.BodyPublisher build() {
        List<byte[]> body = new ArrayList<>(parts);
        body.add(text("--" + boundary + "--\r\n"));
        return HttpRequest.BodyPublishers.ofByteArrays(body);
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
