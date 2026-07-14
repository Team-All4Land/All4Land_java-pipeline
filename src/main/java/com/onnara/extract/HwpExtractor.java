package com.onnara.extract;

import com.onnara.extract.model.ExtractedCell;
import com.onnara.extract.model.ExtractedDocument;
import com.onnara.extract.model.ExtractedImage;
import com.onnara.extract.model.ExtractedTable;

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

import java.util.ArrayList;
import java.util.List;

/**
 * HWP(한글 5.0 바이너리) 파일 파서.
 *
 * 사용 라이브러리: kr.dogfoot:hwplib (Apache-2.0, neolord0 개발)
 *   - HWPReader.fromFile(path) -> HWPFile
 *   - hwpFile.getBodyText().getSectionList() -> 구역(Section) 목록
 *   - section.getParagraphs() -> 문단 배열, paragraph.getNormalString() -> 순수 텍스트
 *     (표/그림 같은 컨트롤 문자는 자동으로 제외되어 반환되므로 Python 버전에서
 *      직접 처리했던 '인라인 컨트롤 placeholder 건너뛰기' 로직이 필요 없음)
 *   - paragraph.getControlList()에서 ControlType.Table을 찾아 표 파싱
 *   - hwpFile.getBinData().getEmbeddedBinaryDataList()에서 이미지 바이너리를
 *     이름/데이터 그대로 꺼낼 수 있음 (압축 해제는 라이브러리가 이미 처리해줌)
 *
 * 한계: Python hwp5_extractor.py와 마찬가지로, 이미지는 '어느 문단에 정확히
 * 붙어있었는지'까지는 복원하지 않고 문서 전체의 BinData를 통째로 추출한다.
 */
public class HwpExtractor implements DocumentExtractor{

    public ExtractedDocument parse(String path) throws Exception {
        HWPFile hwpFile = HWPReader.fromFile(path);
        ExtractedDocument doc = new ExtractedDocument(path);

        int tableSeq = 0;
        for (Section section : hwpFile.getBodyText().getSectionList()) {
            Paragraph[] paragraphs = section.getParagraphs();

            // 캡션 매칭용으로 문단별 텍스트를 먼저 뽑아둔다 (표 자리도 빈 문자열로 유지해
            // 인덱스가 밀리지 않게 함 - Python 버전의 para_texts와 동일한 접근)
            List<String> paraTexts = new ArrayList<>();
            for (Paragraph p : paragraphs) {
                paraTexts.add(p.getNormalString());
            }

            for (int idx = 0; idx < paragraphs.length; idx++) {
                String text = paraTexts.get(idx);
                if (text != null && !text.trim().isEmpty()) {
                    doc.getParagraphs().add(text.trim());
                }

                if (paragraphs[idx].getControlList() != null) {
                    for (Control control : paragraphs[idx].getControlList()) {
                        if (control.getType() == ControlType.Table) {
                            ControlTable ct = (ControlTable) control;
                            ExtractedTable table = parseTable(ct, "tbl_" + (tableSeq++));
                            table.setCaption(TextPatterns.nearestCaption(paraTexts, idx));
                            doc.getTables().add(table);
                        }
                    }
                }
            }
        }

        // 이미지: 문서 전체의 BinData를 통째로 추출 (위치 상관관계는 복원하지 않음)
        if (hwpFile.getBinData() != null) {
            int i = 0;
            for (EmbeddedBinaryData ebd : hwpFile.getBinData().getEmbeddedBinaryDataList()) {
                ExtractedImage img = new ExtractedImage(
                        String.valueOf(i++), ebd.getName(), ebd.getData());
                img.setOriginalFilename(ebd.getName());
                doc.getImages().add(img);
            }
        }

        String guessedTitle = TextPatterns.guessTitleFromTables(doc.getTables());
        if (guessedTitle != null) {
            doc.setOriginalTitle(doc.getTitle());
            doc.setTitle(guessedTitle);
        }

        return doc;
    }

    private ExtractedTable parseTable(ControlTable ct, String tableId) throws Exception {
        int rowCount = ct.getTable().getRowCount();
        int colCount = ct.getTable().getColumnCount();

        List<List<String>> grid = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < colCount; c++) row.add("");
            grid.add(row);
        }

        List<ExtractedCell> cells = new ArrayList<>();

        for (Row row : ct.getRowList()) {
            for (Cell cell : row.getCellList()) {
                ListHeaderForCell lh = cell.getListHeader();
                int colAddr = lh.getColIndex();
                int rowAddr = lh.getRowIndex();
                int colSpan = Math.max(1, lh.getColSpan());
                int rowSpan = Math.max(1, lh.getRowSpan());

                String cellText = cell.getParagraphList().getNormalString().trim();

                cells.add(new ExtractedCell(rowAddr, colAddr, rowSpan, colSpan, cellText));

                for (int r = rowAddr; r < Math.min(rowAddr + rowSpan, rowCount); r++) {
                    for (int c = colAddr; c < Math.min(colAddr + colSpan, colCount); c++) {
                        grid.get(r).set(c, cellText);
                    }
                }
            }
        }

        return new ExtractedTable(tableId, rowCount, colCount, grid, cells);
    }
}
