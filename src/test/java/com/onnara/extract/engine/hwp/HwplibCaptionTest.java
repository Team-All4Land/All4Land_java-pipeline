package com.onnara.extract.engine.hwp;

import com.onnara.extract.common.model.RawContent;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.RawTable;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bindata.EmbeddedBinaryData;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPicture;
import kr.dogfoot.hwplib.object.bodytext.control.gso.GsoControlType;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.ParagraphList;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataCompress;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * HWP 표·그림 캡션 추출 검증.
 *
 * <p>픽스처를 hwplib 작성기로 그 자리에서 만든다 — {@code samples/}의 실물 고시문에는
 * 캡션이 달린 문서가 하나도 없어서, 그것만으로는 이 경로가 <b>돌아가는지조차 알 수 없다</b>.
 * ({@code HwpScanDetectorTest}가 같은 이유로 같은 방식을 쓴다.)
 */
class HwplibCaptionTest {

    @TempDir
    Path tempDir;

    private final HwplibExtractor extractor = new HwplibExtractor();

    /** 표에 달린 캡션이 문단이 아니라 표의 전용 필드로 들어와야 한다. */
    @Test
    void readsTableCaption() throws Exception {
        HWPFile file = BlankFileMaker.make();
        Paragraph host = file.getBodyText().getSectionList().get(0).getParagraph(0);
        ControlTable table = newTable(host);
        table.createCaption();
        addText(table.getCaption().getParagraphList(), "<표 1> 공유수면 점용·사용허가 내역");

        RawDocument raw = extractor.extractRaw(save(file, "표캡션.hwp"));

        assertEquals("<표 1> 공유수면 점용·사용허가 내역", firstTable(raw).getCaption());
    }

    /**
     * 그림 캡션은 {@code binItemID}로 BinData와 이어져야 한다.
     *
     * <p>이미지는 본문과 떨어진 BinData에서 통째로 꺼내므로, 이 연결이 끊기면 캡션이
     * 붙지 않거나 <b>엉뚱한 사진에 붙는다.</b>
     */
    @Test
    void linksPictureCaptionToItsImage() throws Exception {
        HWPFile file = BlankFileMaker.make();
        addImage(file, "BIN0001.png");
        Paragraph host = file.getBodyText().getSectionList().get(0).getParagraph(0);
        ControlPicture picture =
                (ControlPicture) host.addNewGsoControl(GsoControlType.Picture);
        picture.getShapeComponentPicture().getPictureInfo().setBinItemID(1);
        picture.createCaption();
        addText(picture.getCaption().getParagraphList(), "[그림 2] 현장 위치도");

        RawDocument raw = extractor.extractRaw(save(file, "그림캡션.hwp"));

        assertEquals(1, raw.getImages().size());
        assertEquals("[그림 2] 현장 위치도", raw.getImages().get(0).getCaption());
    }

    /**
     * 표 안에 들어간 사진의 캡션도 읽어야 한다 — 고시류에서 가장 흔한 붙임 사진 서식이다.
     *
     * <p>최상위 문단만 훑으면 정작 캡션이 달린 사진을 통째로 놓친다.
     */
    @Test
    void readsPictureCaptionNestedInsideATable() throws Exception {
        HWPFile file = BlankFileMaker.make();
        addImage(file, "BIN0002.png");
        Paragraph host = file.getBodyText().getSectionList().get(0).getParagraph(0);
        ParagraphList cell = cellOf(newTable(host));
        Paragraph inCell = cell.addNewParagraph();
        inCell.createCharShape();
        inCell.getCharShape().addParaCharShape(0, 0);
        ControlPicture picture =
                (ControlPicture) inCell.addNewGsoControl(GsoControlType.Picture);
        picture.getShapeComponentPicture().getPictureInfo().setBinItemID(2);
        picture.createCaption();
        addText(picture.getCaption().getParagraphList(), "무단점유물 사진(45-RB-1268)");

        RawDocument raw = extractor.extractRaw(save(file, "표안그림캡션.hwp"));

        assertEquals(1, raw.getImages().size());
        assertEquals("무단점유물 사진(45-RB-1268)", raw.getImages().get(0).getCaption());
    }

    /** 캡션이 없으면 null이다 — 없는 캡션을 지어내지 않는다. */
    @Test
    void leavesCaptionNullWhenThereIsNone() throws Exception {
        HWPFile file = BlankFileMaker.make();
        addImage(file, "BIN0001.png");
        newTable(file.getBodyText().getSectionList().get(0).getParagraph(0));

        RawDocument raw = extractor.extractRaw(save(file, "캡션없음.hwp"));

        assertNull(firstTable(raw).getCaption());
        assertNull(raw.getImages().get(0).getCaption());
    }

    /** raw 문서에서 첫 번째 표를 꺼낸다. */
    private static RawTable firstTable(RawDocument raw) {
        for (RawContent content : raw.getContent()) {
            if (content instanceof RawTable table) {
                return table;
            }
        }
        throw new AssertionError("추출 결과에 표가 없습니다");
    }

    /** 1행 1열 표를 문단에 붙인다. */
    private static ControlTable newTable(Paragraph host) throws Exception {
        ControlTable table = (ControlTable) host.addNewControl(ControlType.Table);
        table.getTable().setRowCount(1);
        table.getTable().setColumnCount(1);
        Row row = table.addNewRow();
        Cell cell = row.addNewCell();
        cell.getListHeader().setParaCount(1);
        cell.getListHeader().setColIndex(0);
        cell.getListHeader().setRowIndex(0);
        cell.getListHeader().setColSpan(1);
        cell.getListHeader().setRowSpan(1);
        addText(cell.getParagraphList(), "값");
        return table;
    }

    /** 표의 첫 셀 문단 목록. */
    private static ParagraphList cellOf(ControlTable table) {
        return table.getRowList().get(0).getCellList().get(0).getParagraphList();
    }

    /** 텍스트가 담긴 문단을 목록에 추가한다(작성기가 요구하는 글자 모양까지 채운다). */
    private static void addText(ParagraphList list, String text) throws Exception {
        Paragraph paragraph = list.addNewParagraph();
        paragraph.createText();
        paragraph.getText().addString(text);
        paragraph.createCharShape();
        paragraph.getCharShape().addParaCharShape(0, 0);
    }

    /** 임베디드 이미지 1개 — 캡션을 붙일 대상이다. */
    private static void addImage(HWPFile file, String name) {
        EmbeddedBinaryData image = file.getBinData().addNewEmbeddedBinaryData();
        image.setName(name);
        image.setCompressMethod(BinDataCompress.NoCompress);
        image.setData(pngBytes());
    }

    /**
     * {@code ImageSieve}를 통과하는 최소 PNG — 8×8 격자무늬.
     *
     * <p>단색 이미지는 "정보 없음"으로 걸러져 {@code images}에 아예 담기지 않는다(§4.1).
     * 그러면 캡션 연결이 아니라 필터 때문에 테스트가 실패해 원인을 오해하게 된다.
     */
    private static byte[] pngBytes() {
        try {
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    img.setRGB(x, y, ((x / 4) + (y / 4)) % 2 == 0 ? 0xFFFFFF : 0x000000);
                }
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 파일로 써서 추출기에 넘길 경로를 만든다. */
    private Path save(HWPFile file, String name) throws Exception {
        Path path = tempDir.resolve(name);
        HWPWriter.toFile(file, path.toString());
        return path;
    }
}
