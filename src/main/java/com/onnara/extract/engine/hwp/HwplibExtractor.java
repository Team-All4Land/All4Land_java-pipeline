package com.onnara.extract.engine.hwp;

import com.onnara.extract.common.ImageFormats;
import com.onnara.extract.common.model.RawCell;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;
import com.onnara.extract.engine.Extractor;
import com.onnara.extract.engine.image.ImageSieve;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bindata.EmbeddedBinaryData;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.ListHeaderForCell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.reader.HWPReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * HWP(한글 5.0 바이너리) 네이티브 추출 엔진 — hwplib 기반.
 *
 * <p>문단·표를 문서 등장 순서대로 {@code content}에 넣는다.
 * 이미지는 hwplib 특성상 '어느 문단에 붙어있었는지'를 복원할 수 없어
 * 문서 전체의 BinData를 등장 순서대로 추출한다.
 */
public class HwplibExtractor implements Extractor {

    /** {@code hwp} 확장자만 지원한다. */
    @Override
    public boolean supports(String ext) {
        return "hwp".equals(ext);
    }

    /** 엔진 식별자 "hwplib". */
    @Override
    public String engineName() {
        return "hwplib";
    }

    /**
     * 섹션·문단을 순회하며 문단 텍스트와 표를 등장 순서로 content에 담고,
     * 문서 전체 BinData 이미지를 메타로 추가한다.
     */
    @Override
    public RawDocument extractRaw(Path file) throws IOException {
        HWPFile hwpFile = read(file);
        RawDocument raw = new RawDocument(file.getFileName().toString(), "hwp", false);

        for (Section section : hwpFile.getBodyText().getSectionList()) {
            for (Paragraph paragraph : section.getParagraphs()) {
                String text;
                try {
                    text = paragraph.getNormalString();
                } catch (Exception e) {
                    throw new IOException("HWP 문단 텍스트 추출 실패: " + file, e);
                }
                if (text != null && !text.trim().isEmpty()) {
                    raw.getContent().add(new RawParagraph(text.trim()));
                }
                if (paragraph.getControlList() != null) {
                    for (Control control : paragraph.getControlList()) {
                        if (control.getType() == ControlType.Table) {
                            raw.getContent().add(parseTable((ControlTable) control, file));
                        }
                    }
                }
            }
        }

        for (ImageEntry entry : collectImages(hwpFile, stemOf(file))) {
            raw.getImages().add(new RawImage(entry.name, entry.data.length));
        }
        return raw;
    }

    /** BinData 이미지를 outDir에 쓰고 저장 경로 목록을 반환한다(extractRaw의 이미지 순서·이름과 일치). */
    @Override
    public List<Path> saveImages(Path file, Path outDir) throws IOException {
        HWPFile hwpFile = read(file);
        List<Path> saved = new ArrayList<>();
        for (ImageEntry entry : collectImages(hwpFile, stemOf(file))) {
            Files.createDirectories(outDir);
            Path dest = outDir.resolve(entry.name);
            Files.write(dest, entry.data);
            saved.add(dest);
        }
        return saved;
    }

    /** hwplib으로 파일을 파싱한다. 실패 시 IOException으로 감싼다. */
    private static HWPFile read(Path file) throws IOException {
        try {
            return HWPReader.fromFile(file.toString());
        } catch (Exception e) {
            throw new IOException("HWP 파일을 읽을 수 없습니다: " + file, e);
        }
    }

    /**
     * hwplib ControlTable을 raw 표 모델로 변환한다. 셀의 행/열/병합 span을 읽어
     * cells를 채우고, 병합 셀 텍스트를 덮인 모든 칸에 복제해 grid를 구성한다.
     */
    private static RawTable parseTable(ControlTable ct, Path file) throws IOException {
        int rowCount = ct.getTable().getRowCount();
        int colCount = ct.getTable().getColumnCount();

        List<List<String>> grid = new ArrayList<>(rowCount);
        for (int r = 0; r < rowCount; r++) {
            List<String> row = new ArrayList<>(colCount);
            for (int c = 0; c < colCount; c++) {
                row.add("");
            }
            grid.add(row);
        }

        List<RawCell> cells = new ArrayList<>();
        for (Row row : ct.getRowList()) {
            for (Cell cell : row.getCellList()) {
                ListHeaderForCell lh = cell.getListHeader();
                int colAddr = lh.getColIndex();
                int rowAddr = lh.getRowIndex();
                int colSpan = Math.max(1, lh.getColSpan());
                int rowSpan = Math.max(1, lh.getRowSpan());

                String cellText;
                try {
                    cellText = cell.getParagraphList().getNormalString().trim();
                } catch (Exception e) {
                    throw new IOException("HWP 셀 텍스트 추출 실패: " + file, e);
                }

                cells.add(new RawCell(rowAddr, colAddr, rowSpan, colSpan, cellText));
                for (int r = rowAddr; r < Math.min(rowAddr + rowSpan, rowCount); r++) {
                    for (int c = colAddr; c < Math.min(colAddr + colSpan, colCount); c++) {
                        grid.get(r).set(c, cellText);
                    }
                }
            }
        }
        return new RawTable(rowCount, colCount, cells, grid);
    }

    /**
     * 문서 전체 BinData를 (이름, 바이트)로 수집 — extractRaw/saveImages 순서·이름 일치 보장.
     *
     * <p>정보가 없는 이미지(흰 바탕에 표식 하나 등)와 도장은 {@link ImageSieve}가 걸러낸다.
     * 번호는 통과한 것에만 매겨 연속되게 한다.
     */
    private static List<ImageEntry> collectImages(HWPFile hwpFile, String stem) {
        List<ImageEntry> out = new ArrayList<>();
        if (hwpFile.getBinData() == null) {
            return out;
        }
        for (EmbeddedBinaryData ebd : hwpFile.getBinData().getEmbeddedBinaryDataList()) {
            byte[] data = ebd.getData();
            if (data == null || data.length == 0) {
                continue;
            }
            if (!ImageSieve.accept(data)) {
                continue;
            }
            out.add(new ImageEntry(
                    stem + "_img" + out.size() + "." + ImageFormats.extensionFor(data), data));
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
