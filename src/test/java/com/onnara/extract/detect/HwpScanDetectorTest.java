package com.onnara.extract.detect;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bindata.EmbeddedBinaryData;
import kr.dogfoot.hwplib.object.bodytext.control.ControlHeader;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.header.HeaderFooterApplyPage;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlRectangle;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 사진이 지면 대부분을 차지하는 네이티브 HWP가 스캔본으로 오판되지 않는지 검증한다.
 *
 * <p>실제로 겪은 오판이 근거다 — 붙임 현장사진 고시(본문 144자 중 105자가 표 셀 안)가
 * 최상위 문단만 세는 판별기에서 39자로 집계돼 스캔본으로 넘어갔고, 그대로 DB에
 * {@code is_scanned = true}로 적재됐다. 텍스트가 어느 컨테이너에 있든 세야 한다.
 *
 * <p>픽스처는 hwplib 작성기로 그 자리에서 만든다. 오판을 재현하는 데 필요한 건
 * "텍스트의 위치"와 "이미지의 존재"뿐이라, 수 MB짜리 실물 고시문을 저장소에 두는 것보다
 * 조건을 한 줄로 드러내는 편이 회귀 원인을 읽기 쉽다.
 */
class HwpScanDetectorTest {

    @TempDir
    Path tempDir;

    /** 텍스트가 담긴 문단을 목록에 추가한다(작성기가 요구하는 글자 모양까지 채운다). */
    private static void addText(ParagraphList list, String text) throws Exception {
        Paragraph paragraph = list.addNewParagraph();
        paragraph.createText();
        paragraph.getText().addString(text);
        paragraph.createCharShape();
        paragraph.getCharShape().addParaCharShape(0, 0);
    }

    /** 1행 1열 표를 문단에 붙이고 그 셀의 문단 목록을 돌려준다. */
    private static ParagraphList addTable(Paragraph host) throws Exception {
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
        return cell.getParagraphList();
    }

    /** 임베디드 이미지 1개를 넣는다 — 스캔 판정의 전제 조건(OCR로 읽을 대상)이다. */
    private static void addImage(HWPFile file) {
        EmbeddedBinaryData image = file.getBinData().addNewEmbeddedBinaryData();
        image.setName("BIN0001.png");
        image.setCompressMethod(BinDataCompress.NoCompress);
        image.setData(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'});
    }

    /** 파일로 써서 판별기에 넘길 경로를 만든다. */
    private Path save(HWPFile file, String name) throws Exception {
        Path path = tempDir.resolve(name);
        HWPWriter.toFile(file, path.toString());
        return path;
    }

    /** 실제 오판 사례: 본문이 전부 표 안에 있는 붙임 사진 고시. */
    @Test
    void textOnlyInTableCellIsNotScanned() throws Exception {
        HWPFile file = BlankFileMaker.make();
        addText(addTable(file.getBodyText().getSectionList().get(0).getParagraph(0)),
                "무단점유물 사진(85-RB-1060) 포항시 확인결과 말소된 장비임");
        addImage(file);

        assertFalse(new HwpScanDetector().isScanned(save(file, "표셀.hwp")),
                "표 셀에만 텍스트가 있어도 네이티브여야 함");
    }

    /** 붙임 사진 서식에서 흔한, 표 안에 설명 표를 한 겹 더 둔 구조. */
    @Test
    void textInNestedTableIsNotScanned() throws Exception {
        HWPFile file = BlankFileMaker.make();
        ParagraphList outerCell = addTable(file.getBodyText().getSectionList().get(0).getParagraph(0));
        Paragraph host = outerCell.addNewParagraph();
        host.createCharShape();
        host.getCharShape().addParaCharShape(0, 0);
        addText(addTable(host), "위치도 경북 포항시 북구 흥해읍 죽천리 173-3 지선");
        addImage(file);

        assertFalse(new HwpScanDetector().isScanned(save(file, "중첩표.hwp")),
                "중첩 표 안의 텍스트도 세야 함");
    }

    /** 사진 설명을 캡션으로 다는 서식. */
    @Test
    void textInPictureCaptionIsNotScanned() throws Exception {
        HWPFile file = BlankFileMaker.make();
        ControlRectangle shape = (ControlRectangle) file.getBodyText().getSectionList().get(0)
                .getParagraph(0).addNewGsoControl(GsoControlType.Rectangle);
        shape.createCaption();
        addText(shape.getCaption().getParagraphList(), "무단점유물 사진(45-RB-1268)");
        addImage(file);

        assertFalse(new HwpScanDetector().isScanned(save(file, "캡션.hwp")),
                "캡션에만 텍스트가 있어도 네이티브여야 함");
    }

    /** 사진 설명을 글상자로 얹는 서식. */
    @Test
    void textInTextBoxIsNotScanned() throws Exception {
        HWPFile file = BlankFileMaker.make();
        ControlRectangle shape = (ControlRectangle) file.getBodyText().getSectionList().get(0)
                .getParagraph(0).addNewGsoControl(GsoControlType.Rectangle);
        shape.createTextBox();
        addText(shape.getTextBox().getParagraphList(), "무단점유 현장 사진 붙임 자료");
        addImage(file);

        assertFalse(new HwpScanDetector().isScanned(save(file, "글상자.hwp")),
                "글상자에만 텍스트가 있어도 네이티브여야 함");
    }

    /** 어느 컨테이너에도 텍스트가 없으면 본문이 이미지 안에 있다고 본다. */
    @Test
    void noNativeTextWithImageIsScanned() throws Exception {
        HWPFile file = BlankFileMaker.make();
        addImage(file);

        assertTrue(new HwpScanDetector().isScanned(save(file, "본문없음.hwp")),
                "본문 텍스트가 전혀 없고 이미지가 있으면 스캔본");
    }

    /** 이미지가 없으면 OCR로 얻을 것도 없으므로 스캔본이 아니다(빈 문서를 OCR로 보내지 않는다). */
    @Test
    void noTextAndNoImageIsNotScanned() throws Exception {
        assertFalse(new HwpScanDetector().isScanned(save(BlankFileMaker.make(), "빈문서.hwp")),
                "읽을 이미지가 없으면 스캔본이 아님");
    }

    /**
     * 머리말은 본문으로 세지 않는다 — 임계치가 한 글자라 이걸 세면 스캔본이 곧바로
     * 네이티브로 뒤집힌다(진짜 스캔본에도 머리글·쪽번호는 남아 있다).
     */
    @Test
    void headerTextIsNotCountedAsBody() throws Exception {
        HWPFile file = BlankFileMaker.make();
        ControlHeader header = (ControlHeader) file.getBodyText().getSectionList().get(0)
                .getParagraph(0).addNewControl(ControlType.Header);
        header.getHeader().setApplyPage(HeaderFooterApplyPage.BothPage);
        addText(header.getParagraphList(), "포항시 고시 제2026-27호 - 1 -");
        addImage(file);

        assertTrue(new HwpScanDetector().isScanned(save(file, "머리말만.hwp")),
                "머리말만 있는 문서는 본문이 없는 것으로 봐야 함");
    }
}
