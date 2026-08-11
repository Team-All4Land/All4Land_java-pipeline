package com.onnara.extract.engine.hwp;

import com.onnara.extract.common.ImageFormats;
import com.onnara.extract.detect.EncryptionProbe;
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
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlContainer;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPicture;
import kr.dogfoot.hwplib.object.bodytext.control.gso.GsoControl;
import kr.dogfoot.hwplib.object.bodytext.control.gso.caption.Caption;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.ListHeaderForCell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.ParagraphList;
import kr.dogfoot.hwplib.reader.HWPReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HWP(한글 5.0 바이너리) 네이티브 추출 엔진 — hwplib 기반.
 *
 * <p>문단·표를 문서 등장 순서대로 {@code content}에 넣는다.
 * 이미지는 hwplib 특성상 '어느 문단에 붙어있었는지'를 복원할 수 없어
 * 문서 전체의 BinData를 등장 순서대로 추출한다.
 */
public class HwplibExtractor implements Extractor {

    /** BinData 스트림명 — {@code BIN%04X.확장자}. 캡처 그룹이 16진수 binItemID다. */
    private static final Pattern BIN_STREAM_NAME =
            Pattern.compile("(?i)BIN([0-9A-F]+)(?:\\..*)?");

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

        Map<Integer, String> captions = collectPictureCaptions(hwpFile);
        for (ImageEntry entry : collectImages(hwpFile, stemOf(file))) {
            RawImage image = new RawImage(entry.name, entry.data.length);
            image.setCaption(captions.get(entry.binItemId));
            raw.getImages().add(image);
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

    /**
     * hwplib으로 파일을 파싱한다. 실패 시 IOException으로 감싼다.
     *
     * <p>잠긴 문서는 먼저 걸러 낸다. 그대로 넘기면 hwplib이 암호문을 구조로 읽으려다
     * {@code "This is not paragraph."}로 죽어, 원인 체인에 암호 얘기가 한마디도 남지 않는다.
     */
    private static HWPFile read(Path file) throws IOException {
        EncryptionProbe.requireUnlocked(file);
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
        RawTable table = new RawTable(rowCount, colCount, cells, grid);
        table.setCaption(captionTextOf(ct.getCaption()));
        return table;
    }

    /**
     * 문서 전체를 훑어 {@code binItemID → 캡션} 맵을 만든다.
     *
     * <p>이미지는 {@link #collectImages}가 BinData에서 통째로 꺼내므로 본문과 끊겨 있다.
     * 캡션은 반대로 본문 쪽 그림 컨트롤에만 달려 있어, 둘을 잇는 열쇠가 필요하다 —
     * 그림 컨트롤의 {@code binItemID}와 BinData 스트림명이 그 열쇠다.
     *
     * <p>표 셀·묶음 안까지 재귀한다. 고시류는 현장사진이 표 안에 들어가는 일이 흔해,
     * 최상위 문단만 보면 정작 캡션이 붙은 사진을 전부 놓친다.
     */
    private static Map<Integer, String> collectPictureCaptions(HWPFile hwpFile) {
        Map<Integer, String> captions = new HashMap<>();
        for (Section section : hwpFile.getBodyText().getSectionList()) {
            scanParagraphs(section.getParagraphs(), captions);
        }
        return captions;
    }

    /** 문단 배열을 훑어 그림 캡션을 모은다(표 셀·묶음 안까지 내려간다). */
    private static void scanParagraphs(Paragraph[] paragraphs, Map<Integer, String> captions) {
        if (paragraphs == null) {
            return;
        }
        for (Paragraph paragraph : paragraphs) {
            if (paragraph == null || paragraph.getControlList() == null) {
                continue;
            }
            for (Control control : paragraph.getControlList()) {
                scanControl(control, captions);
            }
        }
    }

    /** 컨트롤 하나를 훑는다 — 그림이면 캡션을 기록하고, 표·묶음이면 한 겹 더 내려간다. */
    private static void scanControl(Control control, Map<Integer, String> captions) {
        if (control == null) {
            return;
        }
        if (control.getType() == ControlType.Table) {
            for (Row row : ((ControlTable) control).getRowList()) {
                for (Cell cell : row.getCellList()) {
                    scanParagraphs(toArray(cell.getParagraphList()), captions);
                }
            }
            return;
        }
        if (control instanceof ControlPicture picture) {
            String caption = captionTextOf(picture.getCaption());
            if (caption != null && picture.getShapeComponentPicture() != null
                    && picture.getShapeComponentPicture().getPictureInfo() != null) {
                captions.putIfAbsent(
                        picture.getShapeComponentPicture().getPictureInfo().getBinItemID(), caption);
            }
            return;
        }
        if (control instanceof ControlContainer container) {
            for (GsoControl child : container.getChildControlList()) {
                scanControl(child, captions);
            }
        }
    }

    /** 캡션 문단들을 한 줄 텍스트로 — 비어 있으면 null. */
    private static String captionTextOf(Caption caption) {
        if (caption == null || caption.getParagraphList() == null) {
            return null;
        }
        String text;
        try {
            text = caption.getParagraphList().getNormalString();
        } catch (Exception e) {
            // 캡션을 못 읽었다고 표·그림 자체를 버릴 이유는 없다
            return null;
        }
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** 문단 목록을 배열로 꺼낸다(같은 순회 경로로 한 겹 더 내려가기 위해). */
    private static Paragraph[] toArray(ParagraphList list) {
        if (list == null) {
            return new Paragraph[0];
        }
        Paragraph[] paragraphs = new Paragraph[list.getParagraphCount()];
        for (int i = 0; i < paragraphs.length; i++) {
            paragraphs[i] = list.getParagraph(i);
        }
        return paragraphs;
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
            // BinData 스트림명("BIN0001.jpg")은 매직바이트 판별이 실패했을 때만 쓰는 확장자 힌트다.
            // 이게 없으면 wmf/emf/ole처럼 서명이 제각각인 형식이 전부 .bin으로 떨어진다.
            String ext = ImageFormats.extensionFor(data, ebd.getName());
            String name = stem + "_img" + out.size() + "." + ext;
            if (ImageFormats.isUnknown(ext)) {
                System.err.println("[경고] 알 수 없는 이미지 형식이라 " + name + "으로 저장합니다"
                        + " (매직 " + ImageFormats.magicOf(data) + ", 힌트 " + ebd.getName() + ")");
            }
            out.add(new ImageEntry(name, data, binItemIdOf(ebd.getName())));
        }
        return out;
    }

    /**
     * BinData 스트림명에서 {@code binItemID}를 뽑는다 — {@code "BIN0001.jpg"} → 1.
     *
     * <p>번호는 <b>16진수</b>다({@code BIN%04X}). 10진수로 읽으면 항목이 10개를 넘는
     * 순간부터 캡션이 엉뚱한 그림에 붙는다.
     *
     * @return 뽑지 못하면 -1 (어떤 캡션과도 매칭되지 않는 값)
     */
    private static int binItemIdOf(String streamName) {
        if (streamName == null) {
            return -1;
        }
        // 확장자에도 16진수로 보이는 글자가 섞인다(.bmp의 'b' 등) — 이름 형태로 잘라 낸다
        Matcher matcher = BIN_STREAM_NAME.matcher(streamName);
        if (!matcher.matches()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 확장자를 제외한 파일명(이미지 파일명 접두사로 사용). */
    private static String stemOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** 수집된 이미지 1건: 저장 파일명·원본 바이트, 그리고 캡션을 잇는 열쇠인 binItemID. */
    private record ImageEntry(String name, byte[] data, int binItemId) {
    }
}
