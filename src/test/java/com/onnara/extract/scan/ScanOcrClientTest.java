package com.onnara.extract.scan;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.onnara.extract.common.model.RawDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanOcrClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ScanOcrClient clientFor(HttpServer srv) {
        this.server = srv;
        srv.start();
        int port = srv.getAddress().getPort();
        return new ScanOcrClient(new ScanOcrConfig("http://127.0.0.1:" + port, Duration.ofSeconds(5), 2));
    }

    @Test
    void healthReturnsTrueOnOkStatus() throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/health", exchange -> respond(exchange, 200,
                "{\"status\":\"ok\",\"model_loaded\":true}"));
        ScanOcrClient client = clientFor(srv);

        assertTrue(client.health());
    }

    @Test
    void healthReturnsFalseWhenUnreachable() {
        // 서버를 시작하지 않은 상태의 포트 — 연결 자체가 실패해야 함
        ScanOcrClient client = new ScanOcrClient(
                new ScanOcrConfig("http://127.0.0.1:1", Duration.ofSeconds(2), 0));
        assertTrue(!client.health());
    }

    @Test
    void parseImagesSendsMultipartWithExpectedFields(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("img0.png");
        // PNG 매직바이트만 있으면 충분 (서버는 내용을 읽지 않음)
        Files.write(image, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0});

        StringBuilder capturedBody = new StringBuilder();
        java.util.concurrent.atomic.AtomicReference<String> contentType = new java.util.concurrent.atomic.AtomicReference<>();

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/parse", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = exchange.getRequestBody().readAllBytes();
            capturedBody.append(new String(body, StandardCharsets.UTF_8));
            respond(exchange, 200, rawJson("scan.hwpx", "hwpx"));
        });
        ScanOcrClient client = clientFor(srv);

        RawDocument raw = client.parseImages(List.of(image), "scan.hwpx", "hwpx");

        assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
        assertTrue(capturedBody.toString().contains("name=\"source_file\""));
        assertTrue(capturedBody.toString().contains("scan.hwpx"));
        assertTrue(capturedBody.toString().contains("name=\"images\"; filename=\"img0.png\""));
        assertTrue(raw.isScanned(), "OCR 응답은 is_scanned=true로 강제되어야 함");
        assertEquals("scan.hwpx", raw.getSourceFile());
    }

    @Test
    void retriesOn500ThenSucceeds(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("img0.png");
        Files.write(image, new byte[]{1, 2, 3});

        AtomicInteger callCount = new AtomicInteger(0);
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/parse", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (callCount.getAndIncrement() == 0) {
                respond(exchange, 500, "{\"error\":\"boom\"}");
            } else {
                respond(exchange, 200, rawJson("x.pdf", "pdf"));
            }
        });
        ScanOcrClient client = clientFor(srv);

        RawDocument raw = client.parseImages(List.of(image), "x.pdf", "pdf");

        assertEquals(2, callCount.get());
        assertEquals("x.pdf", raw.getSourceFile());
    }

    @Test
    void doesNotRetryOn422() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/parse", exchange -> {
            exchange.getRequestBody().readAllBytes();
            callCount.incrementAndGet();
            respond(exchange, 422, "{\"error\":\"bad request\"}");
        });
        ScanOcrClient client = clientFor(srv);

        Path pdf = Files.createTempFile("dummy", ".pdf");
        try {
            Files.write(pdf, new byte[]{1, 2, 3});
            assertThrows(ScanOcrException.class, () -> client.parsePdf(pdf, "x.pdf"));
            assertEquals(1, callCount.get(), "422는 재시도하면 안 됨");
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    private static String rawJson(String sourceFile, String fileType) {
        return "{\"source_file\":\"" + sourceFile + "\",\"file_type\":\"" + fileType + "\","
                + "\"is_scanned\":true,\"content\":[],\"images\":[],\"markdown\":\"# ignored\"}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
