package com.onnara.extract.scan;

import com.onnara.extract.common.model.RawContent;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.RawParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 사진 속 본문을 raw JSON에 합치는 계약 검증 — 스텁 셸 스크립트를 OCR CLI 자리에 세운다.
 *
 * <p>확인 대상: 도장·로고 크기 필터, content 병합과 출처 표시, 이미지별 ocr_text 채우기.
 */
@DisabledOnOs(OS.WINDOWS)
class ImageOcrEnricherTest {

    @TempDir
    Path tempDir;

    /** 지정 크기의 PNG를 만든다(크기 필터 검증용 — 내용은 무관). */
    private Path png(String name, int width, int height) throws IOException {
        Path file = tempDir.resolve(name);
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    /** 넘겨받은 입력 이미지마다 문단 1개를 source_image와 함께 돌려주는 스텁. */
    private Path echoScript() throws IOException {
        String script = """
                out=
                prev=
                inputs=
                for a in "$@"; do
                  if [ "$prev" = "--output" ]; then out="$a"; fi
                  case "$a" in
                    --*) ;;
                    *) case "$prev" in --*) ;; *) inputs="$inputs $a";; esac;;
                  esac
                  prev="$a"
                done
                items=
                for f in $inputs; do
                  base=$(basename "$f")
                  if [ -n "$items" ]; then items="$items,"; fi
                  items="$items{\\"type\\":\\"paragraph\\",\\"text\\":\\"읽음 $base\\",\\"source_image\\":\\"$base\\"}"
                done
                printf '{"source_file":"x","file_type":"hwp","is_scanned":true,"content":[%s],"images":[]}' "$items" > "$out"
                """;
        Path file = Files.createTempFile(tempDir, "stub", ".sh");
        Files.writeString(file, script);
        return file;
    }

    private ImageOcrEnricher enricher(Path script, boolean enabled, int minDimension) {
        ScanOcrConfig config = new ScanOcrConfig(
                "/bin/sh", script, Duration.ofSeconds(20), enabled, minDimension);
        return new ImageOcrEnricher(new ScanOcrRunner(config), config);
    }

    /** name/size만 있던 이미지에 대해 content와 ocr_text가 함께 채워지는지 검증한다. */
    @Test
    void mergesOcrContentAndPerImageText() throws Exception {
        Path photo = png("doc_img0.png", 1200, 900);
        RawDocument raw = new RawDocument("doc.hwp", "hwp", false);
        raw.getContent().add(new RawParagraph("붙임 무단점유 현장 사진"));
        raw.getImages().add(new RawImage("doc_img0.png", Files.size(photo)));

        int merged = enricher(echoScript(), true, 400).enrich(raw, List.of(photo), "hwp");

        assertEquals(1, merged);
        assertEquals(2, raw.getContent().size(), "네이티브 문단은 그대로 두고 뒤에 붙어야 함");
        RawContent added = raw.getContent().get(1);
        assertEquals("doc_img0.png", added.getSourceImage(), "출처 이미지를 남겨야 함");
        assertEquals("읽음 doc_img0.png", ((RawParagraph) added).getText());
        assertEquals("읽음 doc_img0.png", raw.getImages().get(0).getOcrText());
    }

    /** 도장·로고 크기의 이미지는 후보에서 빠져 OCR을 아예 실행하지 않는다. */
    @Test
    void skipsStampSizedImages() throws Exception {
        Path stamp = png("doc_img0.png", 120, 120);
        RawDocument raw = new RawDocument("doc.hwp", "hwp", false);
        raw.getImages().add(new RawImage("doc_img0.png", Files.size(stamp)));

        // 실행되면 실패하는 스크립트를 세워, 후보가 없으면 호출 자체가 없음을 확인한다
        Path failing = Files.createTempFile(tempDir, "never", ".sh");
        Files.writeString(failing, "exit 9\n");

        assertEquals(0, enricher(failing, true, 400).enrich(raw, List.of(stamp), "hwp"));
        assertTrue(raw.getContent().isEmpty());
        assertNull(raw.getImages().get(0).getOcrText());
    }

    /** 크기를 못 재는 파일은 후보에 남긴다 — 판단 불가를 '작다'로 보면 본문을 잃는다. */
    @Test
    void keepsUnreadableImagesAsCandidates() throws Exception {
        Path broken = tempDir.resolve("doc_img0.bin");
        Files.write(broken, new byte[]{1, 2, 3, 4});

        List<Path> candidates = enricher(echoScript(), true, 400).candidates(List.of(broken));

        assertEquals(List.of(broken), candidates);
    }

    /** OCR 실패는 예외로 올린다 — 스캔본이면 치명적, 네이티브면 호출부가 잡아 경고로 낮춘다. */
    @Test
    void propagatesOcrFailure() throws Exception {
        Path photo = png("doc_img0.png", 1200, 900);
        Path failing = Files.createTempFile(tempDir, "fail", ".sh");
        Files.writeString(failing, "echo 'paddle 미설치' >&2\nexit 3\n");
        RawDocument raw = new RawDocument("doc.hwp", "hwp", false);
        raw.getImages().add(new RawImage("doc_img0.png", Files.size(photo)));

        ImageOcrEnricher enricher = enricher(failing, true, 400);
        assertThrows(ScanOcrException.class, () -> enricher.enrich(raw, List.of(photo), "hwp"));
    }

    /** 설정으로 끄면 실행기가 준비돼 있어도 사용 불가로 보고한다(--no-image-ocr 경로). */
    @Test
    void disabledConfigIsNotUsable() throws Exception {
        assertTrue(enricher(echoScript(), true, 400).isUsable());
        assertNotNull(enricher(echoScript(), false, 400));
        assertEquals(false, enricher(echoScript(), false, 400).isUsable());
    }
}
