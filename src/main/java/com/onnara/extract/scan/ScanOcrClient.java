package com.onnara.extract.scan;

import com.fasterxml.jackson.databind.JsonNode;
import com.onnara.extract.common.ImageFormats;
import com.onnara.extract.common.Json;
import com.onnara.extract.common.model.RawDocument;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Python OCR 서비스(ocr-service) HTTP 클라이언트 — §7 계약.
 *
 * <p>역할 분담: 임베디드 이미지 추출은 Java Extractor가 담당하고, 이 클라이언트는
 * "이미지/PDF → 구조화 텍스트" 추론만 요청한다. 응답은 §4 raw JSON 그대로이며
 * {@code markdown} 등 계약 외 필드는 {@link Json#MAPPER}가 무시한다.
 */
public final class ScanOcrClient {

    private final ScanOcrConfig config;
    private final HttpClient httpClient;

    public ScanOcrClient(ScanOcrConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** GET /health — 서비스가 기동되어 모델을 로드했는지 확인. */
    public boolean health() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/health"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            JsonNode body = Json.MAPPER.readTree(response.body());
            return "ok".equals(body.path("status").asText(null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** 스캔 PDF 원본을 서비스로 전송한다(서비스가 페이지 렌더링 후 VLM 추론). */
    public RawDocument parsePdf(Path pdf, String sourceFile) throws IOException {
        try {
            byte[] data = Files.readAllBytes(pdf);
            MultipartBodyPublisher body = new MultipartBodyPublisher()
                    .addField("source_file", sourceFile)
                    .addField("file_type", "pdf")
                    .addFile("file", pdf.getFileName().toString(), data, "application/pdf");
            return callWithRetry(body);
        } catch (IOException e) {
            throw new IOException("PDF 파일을 읽을 수 없습니다: " + pdf, e);
        }
    }

    /** 스캔 HWP/HWPX/HML에서 추출한 임베디드 이미지들을 서비스로 전송한다. */
    public RawDocument parseImages(List<Path> images, String sourceFile, String fileType) throws IOException {
        MultipartBodyPublisher body = new MultipartBodyPublisher()
                .addField("source_file", sourceFile)
                .addField("file_type", fileType);
        for (Path image : images) {
            byte[] data = Files.readAllBytes(image);
            body.addFile("images", image.getFileName().toString(), data,
                    "image/" + ImageFormats.extensionFor(data));
        }
        return callWithRetry(body);
    }

    private RawDocument callWithRetry(MultipartBodyPublisher body) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt <= config.retries(); attempt++) {
            if (attempt > 0) {
                sleep(attempt * 2000L);
            }
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(config.baseUrl() + "/v1/parse"))
                        .timeout(config.timeout())
                        .header("Content-Type", body.contentType())
                        .POST(body.build())
                        .build();
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    RawDocument raw = Json.MAPPER.readValue(response.body(), RawDocument.class);
                    raw.setScanned(true);
                    return raw;
                }
                if (response.statusCode() == 422) {
                    // 요청 자체가 잘못됨 — 재시도해도 동일하게 실패하므로 즉시 중단
                    throw new ScanOcrException(
                            "OCR 서비스 요청 오류(422): " + response.body());
                }
                lastError = new IOException(
                        "OCR 서비스 응답 오류(" + response.statusCode() + "): " + response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("OCR 서비스 호출 중단됨", e);
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw new ScanOcrException("OCR 서비스 호출 실패(재시도 " + config.retries() + "회 소진)", lastError);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
