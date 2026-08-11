package com.onnara.extract.engine.hwp3;

import com.onnara.extract.common.ImageFormats;
import com.onnara.extract.common.model.RawCell;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;
import com.onnara.extract.engine.Extractor;
import com.onnara.extract.engine.image.ImageSieve;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 한글 3.0(HWP Document File V3.00) 네이티브 추출 엔진 — {@link Hwp3Reader} 기반.
 *
 * <p>hwplib이 읽는 HWP 5.0과 컨테이너부터 다른 구버전이다. 확장자는 똑같이 {@code .hwp}라
 * 라우팅은 {@link com.onnara.extract.detect.DocFormat}이 매직바이트로 갈라 준다.
 *
 * <p>문단·표를 문서 등장 순서대로 {@code content}에 담고, 표·그림의 <b>캡션은 뒤따르는
 * 문단</b>으로 넣는다({@link Hwp3Document.Text}). 임베디드 이미지는 파일 태그 영역에서
 * 읽어 메타로 남긴다 — hwplib 경로와 마찬가지로 "어느 문단에 붙어 있었는지"는 복원하지 않는다.
 */
public class Hwp3Extractor implements Extractor {

    /** {@code hwp3} 형식 키만 지원한다(확장자가 아니라 내용으로 판정된 키다). */
    @Override
    public boolean supports(String ext) {
        return "hwp3".equals(ext);
    }

    /** 엔진 식별자 "hwp3". */
    @Override
    public String engineName() {
        return "hwp3";
    }

    /** 본문 항목을 raw 계약으로 옮기고, 임베디드 이미지 메타를 덧붙인다. */
    @Override
    public RawDocument extractRaw(Path file) throws IOException {
        Hwp3Document doc = Hwp3Reader.read(file);
        RawDocument raw = new RawDocument(file.getFileName().toString(), "hwp", false);

        for (Hwp3Document.Item item : doc.items()) {
            if (item instanceof Hwp3Document.Text text) {
                raw.getContent().add(new RawParagraph(text.text()));
            } else if (item instanceof Hwp3Document.Table table) {
                raw.getContent().add(toRawTable(table));
            }
        }
        for (ImageEntry entry : collectImages(doc, stemOf(file))) {
            raw.getImages().add(new RawImage(entry.name, entry.data.length));
        }
        return raw;
    }

    /** 임베디드 이미지를 outDir에 쓰고 저장 경로 목록을 반환한다(extractRaw의 순서·이름과 일치). */
    @Override
    public List<Path> saveImages(Path file, Path outDir) throws IOException {
        Hwp3Document doc = Hwp3Reader.read(file);
        List<Path> saved = new ArrayList<>();
        for (ImageEntry entry : collectImages(doc, stemOf(file))) {
            Files.createDirectories(outDir);
            Path dest = outDir.resolve(entry.name);
            Files.write(dest, entry.data);
            saved.add(dest);
        }
        return saved;
    }

    /**
     * 표를 raw 계약으로 옮긴다 — 셀 목록은 병합 span을 보존하고, grid는 병합 셀 텍스트를
     * 덮인 칸마다 복제해 채운다({@code HwplibExtractor}와 같은 규칙이라 매퍼가 형식별로
     * 다른 모양을 보지 않는다).
     */
    private static RawTable toRawTable(Hwp3Document.Table table) {
        int rowCount = table.rows();
        int colCount = table.cols();

        List<List<String>> grid = new ArrayList<>(rowCount);
        for (int r = 0; r < rowCount; r++) {
            List<String> row = new ArrayList<>(colCount);
            for (int c = 0; c < colCount; c++) {
                row.add("");
            }
            grid.add(row);
        }

        List<RawCell> cells = new ArrayList<>(table.cells().size());
        for (Hwp3Document.Cell cell : table.cells()) {
            cells.add(new RawCell(cell.row(), cell.col(), cell.rowSpan(), cell.colSpan(), cell.text()));
            for (int r = cell.row(); r < Math.min(cell.row() + cell.rowSpan(), rowCount); r++) {
                for (int c = cell.col(); c < Math.min(cell.col() + cell.colSpan(), colCount); c++) {
                    grid.get(r).set(c, cell.text());
                }
            }
        }
        return new RawTable(rowCount, colCount, cells, grid);
    }

    /**
     * 임베디드 이미지를 (이름, 바이트)로 수집 — extractRaw/saveImages 순서·이름 일치 보장.
     *
     * <p>정보가 없는 이미지(흰 바탕에 표식 하나 등)와 도장은 {@link ImageSieve}가 걸러낸다.
     * 번호는 통과한 것에만 매겨 연속되게 한다.
     */
    private static List<ImageEntry> collectImages(Hwp3Document doc, String stem) {
        List<ImageEntry> out = new ArrayList<>();
        for (Hwp3Document.Image image : doc.images()) {
            byte[] data = image.data();
            if (data == null || data.length == 0 || !ImageSieve.accept(data)) {
                continue;
            }
            // 태그의 형식 필드("JPG" 등)는 매직바이트 판별이 실패했을 때만 쓰는 확장자 힌트다
            String ext = ImageFormats.extensionFor(data, image.formatHint());
            String name = stem + "_img" + out.size() + "." + ext;
            if (ImageFormats.isUnknown(ext)) {
                System.err.println("[경고] 알 수 없는 이미지 형식이라 " + name + "으로 저장합니다"
                        + " (매직 " + ImageFormats.magicOf(data) + ", 힌트 " + image.formatHint() + ")");
            }
            out.add(new ImageEntry(name, data));
        }
        return out;
    }

    /** 확장자를 제외한 파일명(이미지 파일명 접두사로 사용). */
    private static String stemOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** 수집된 이미지 1건: 저장 파일명과 원본 바이트. */
    private record ImageEntry(String name, byte[] data) {
    }
}
