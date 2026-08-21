package com.onnara.extract.engine.hml;

import com.onnara.extract.TestFixtures;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HmlExtractor} HML 추출(BOM·표 격자·base64 이미지)의 samples/ 기반 회귀 테스트. */
class HmlExtractorTest {

    private final HmlExtractor extractor = new HmlExtractor();

    /** UTF-8 BOM 문서에서 표가 파싱되고 grid 크기·span이 유효한지 검증한다. */
    @Test
    void parsesUtf8BomAndTableGrid() throws Exception {
        Path file = TestFixtures.sample(
                "1_공유수면 점용·사용 실시계획 준공검사확인증 발급 고시(인천시종합건설본부, 영종도 해안순환도로).hml");
        RawDocument raw = extractor.extractRaw(file);

        assertEquals("hml", raw.getFileExtn());
        assertFalse(raw.isScanYn());
        assertFalse(raw.getContent().isEmpty());

        List<RawTable> tables = raw.getContent().stream()
                .filter(c -> c instanceof RawTable)
                .map(c -> (RawTable) c)
                .collect(Collectors.toList());
        assertFalse(tables.isEmpty(), "표가 없음 (RowCount/ColCount 속성 파싱 확인 대상)");
        for (RawTable t : tables) {
            assertEquals(t.getNRows(), t.getGrid().size(), "n_rows != grid.size()");
            if (t.getNRows() > 0) {
                assertEquals(t.getNCols(), t.getGrid().get(0).size(), "n_cols != grid row width");
            }
            t.getCells().forEach(cell -> {
                assertTrue(cell.getRowSpan() >= 1);
                assertTrue(cell.getColSpan() >= 1);
            });
        }
    }

    /** BINDATA base64 이미지가 디코딩되어 크기가 채워지는지 검증한다. */
    @Test
    void decodesEmbeddedBase64Image() throws Exception {
        Path file = TestFixtures.sample("8_공유수면 점용 사용 변경허가 고시(한국해양소년단).hml");
        RawDocument raw = extractor.extractRaw(file);

        assertFalse(raw.getImages().isEmpty(), "BINDATA 이미지가 디코딩되지 않음");
        assertTrue(raw.getImages().get(0).getSize() > 0);
    }

    /** saveImages 결과가 extractRaw의 이미지 순서·이름과 일치하는지 검증한다. */
    @Test
    void saveImagesMatchesExtractRawOrderAndNames(@TempDir Path tempDir) throws Exception {
        Path file = TestFixtures.sample("8_공유수면 점용 사용 변경허가 고시(한국해양소년단).hml");
        RawDocument raw = extractor.extractRaw(file);
        List<Path> saved = extractor.saveImages(file, tempDir);

        assertEquals(raw.getImages().size(), saved.size());
        for (int i = 0; i < saved.size(); i++) {
            assertEquals(raw.getImages().get(i).getName(), saved.get(i).getFileName().toString());
            assertTrue(Files.exists(saved.get(i)));
        }
    }

    /**
     * 매직바이트로 못 알아보는 형식(WMF 등)은 {@code <BINITEM Format="…">}을 써서 이름을 붙인다 —
     * 이 폴백이 없으면 고시문에 흔한 벡터 이미지가 전부 {@code .bin}으로 떨어진다.
     */
    @Test
    void namesUnsniffableImageFromBinItemFormat(@TempDir Path tempDir) throws Exception {
        // 어느 서명에도 걸리지 않는 바이트 — 판별은 실패하고 힌트만 남는 상황을 만든다
        byte[] opaque = new byte[64];
        Arrays.fill(opaque, (byte) 0x5A);
        Path file = writeHml(tempDir, "힌트폴백.hml", "wmf", opaque);

        RawDocument raw = extractor.extractRaw(file);

        assertEquals(1, raw.getImages().size());
        assertTrue(raw.getImages().get(0).getName().endsWith(".wmf"),
                "BINITEM Format이 쓰이지 않음: " + raw.getImages().get(0).getName());
    }

    /**
     * 매직바이트가 힌트를 이긴다 — Format 속성이 실제 내용과 다르게 저장된 서식이 실재한다.
     */
    @Test
    void magicBytesWinOverBinItemFormat(@TempDir Path tempDir) throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        Path file = writeHml(tempDir, "불일치.hml", "jpg", png);

        RawDocument raw = extractor.extractRaw(file);

        assertEquals(1, raw.getImages().size());
        assertTrue(raw.getImages().get(0).getName().endsWith(".png"),
                "매직바이트가 무시됨: " + raw.getImages().get(0).getName());
    }

    /**
     * {@code Compress="true"}인 BINDATA는 풀어서 저장해야 한다. 압축된 채로 두면 확장자 판별이
     * 실패해 {@code .bin}이 될 뿐 아니라 저장된 파일 자체가 열리지 않는다.
     */
    @Test
    void inflatesCompressedBinData(@TempDir Path tempDir) throws Exception {
        byte[] png = new byte[64];
        System.arraycopy(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, png, 0, 8);
        Path file = writeHml(tempDir, "압축.hml", "png", deflate(png), "true");

        RawDocument raw = extractor.extractRaw(file);
        List<Path> saved = extractor.saveImages(file, tempDir.resolve("images"));

        assertEquals(1, saved.size());
        assertTrue(saved.get(0).getFileName().toString().endsWith(".png"),
                "압축을 풀지 않아 형식이 판별되지 않음: " + saved.get(0).getFileName());
        assertArrayEquals(png, Files.readAllBytes(saved.get(0)), "원본 바이트로 복원돼야 함");
    }

    /** BINDATA 한 건과 그것을 가리키는 PICTURE 하나를 가진 최소 HML을 만든다. */
    private static Path writeHml(Path dir, String name, String format, byte[] data)
            throws Exception {
        return writeHml(dir, name, format, data, null);
    }

    /** {@code compress}가 null이 아니면 BINDATA에 Compress 속성을 붙인다. */
    private static Path writeHml(Path dir, String name, String format, byte[] data, String compress)
            throws Exception {
        String compressAttr = compress == null ? "" : " Compress=\"" + compress + "\"";
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <HWPML>
                  <HEAD>
                    <BINDATALIST Count="1">
                      <BINITEM BinData="1" Format="%s" Type="Embedding"/>
                    </BINDATALIST>
                  </HEAD>
                  <BODY>
                    <SECTION>
                      <P>
                        <TEXT>
                          <PICTURE>
                            <IMAGE BinItem="1"/>
                          </PICTURE>
                        </TEXT>
                      </P>
                    </SECTION>
                  </BODY>
                  <TAIL>
                    <BINDATASTORAGE>
                      <BINDATA Encoding="Base64" Id="1" Size="%d">%s</BINDATA>
                    </BINDATASTORAGE>
                  </TAIL>
                </HWPML>
                """.formatted(format, data.length, Base64.getEncoder().encodeToString(data));
        Path file = dir.resolve(name);
        Files.writeString(file, xml, StandardCharsets.UTF_8);
        return file;
    }

    /** zlib으로 압축한다(HWPML의 Compress="true" 표현을 흉내낸다). */
    private static byte[] deflate(byte[] data) throws Exception {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }
}
