package com.onnara.extract;

import com.onnara.extract.model.ExtractedCell;
import com.onnara.extract.model.ExtractedDocument;
import com.onnara.extract.model.ExtractedImage;
import com.onnara.extract.model.ExtractedTable;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.context_hpf.ManifestItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.ParaListCore;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.RunItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.TItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.FWSpace;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.LineBreak;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.NBSpace;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.NormalText;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.Tab;
import kr.dogfoot.hwpxlib.reader.HWPXReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HWPX(ZIP+XML) 파일 파서.
 *
 * 사용 라이브러리: kr.dogfoot:hwpxlib (Apache-2.0, neolord0 개발)
 *   - HWPXReader.fromFilepath(path) -> HWPXFile
 *   - file.sectionXMLFileList() -> 구역별 SectionXMLFile (ParaListCore 상속)
 *   - Para -> Run -> RunItem 구조. RunItem은 T(텍스트)/Table/Picture 등으로 분기
 *   - T 내부는 NormalText/FWSpace/Tab/LineBreak/NBSpace 등 여러 TItem으로 나뉘는데,
 *     여기서 NormalText만 읽고 나머지(FWSpace 등)를 무시하면 Python의
 *     hwp_hwpx_parser 라이브러리에서 발견됐던 "관리청 -> 관"류 버그가 재현되므로,
 *     모든 TItem 타입을 명시적으로 처리해서 텍스트가 끊기지 않게 한다.
 *   - Picture.img().binaryItemIDRef()로 이미지 참조, Picture.shapeComment().text()로
 *     원본 파일명 등 캡션 후보를 바로 얻을 수 있음 (Python에서 정규식으로 파싱했던 것보다 간단)
 */
public class HwpxExtractor {

    public ExtractedDocument parse(String path) throws Exception {
        HWPXFile hwpxFile = HWPXReader.fromFilepath(path);
        ExtractedDocument doc = new ExtractedDocument(path);

        // 이미지 원본 데이터: content.hpf manifest의 attachedFile에서 id -> bytes로 매핑
        Map<String, byte[]> binDataMap = new HashMap<>();
        for (ManifestItem item : hwpxFile.contentHPFFile().manifest().items()) {
            if (item.hasAttachedFile() && item.attachedFile() != null) {
                binDataMap.put(item.id(), item.attachedFile().data());
            }
        }

        int tableSeq = 0;
        int imageSeq = 0;

        for (int secIdx = 0; secIdx < hwpxFile.sectionXMLFileList().count(); secIdx++) {
            SectionXMLFile section = hwpxFile.sectionXMLFileList().get(secIdx);
            int paraCount = section.countOfPara();

            // 캡션 매칭용으로 문단별 텍스트를 먼저 뽑아둔다
            List<String> paraTexts = new ArrayList<>();
            for (int i = 0; i < paraCount; i++) {
                paraTexts.add(extractParaText(section.getPara(i)));
            }

            for (int idx = 0; idx < paraCount; idx++) {
                Para para = section.getPara(idx);
                String text = paraTexts.get(idx);
                if (text != null && !text.trim().isEmpty()) {
                    doc.getParagraphs().add(text.trim());
                }

                for (int r = 0; r < para.countOfRun(); r++) {
                    Run run = para.getRun(r);
                    for (int it = 0; it < run.countOfRunItem(); it++) {
                        RunItem runItem = run.getRunItem(it);

                        if (runItem instanceof Table) {
                            ExtractedTable table = parseTable((Table) runItem, "tbl_" + (tableSeq++));
                            table.setCaption(TextPatterns.nearestCaption(paraTexts, idx));
                            doc.getTables().add(table);

                            // 표 내부에 그림이 셀로 들어있는 경우까지 처리
                            for (Tr tr : ((Table) runItem).trs()) {
                                for (Tc tc : tr.tcs()) {
                                    for (Picture pic : findPictures(tc.subList())) {
                                        ExtractedImage img = parseImage(pic, binDataMap, imageSeq++);
                                        if (img != null) doc.getImages().add(img);
                                    }
                                }
                            }
                        } else if (runItem instanceof Picture) {
                            ExtractedImage img = parseImage((Picture) runItem, binDataMap, imageSeq++);
                            if (img != null) doc.getImages().add(img);
                        }
                    }
                }
            }
        }

        String guessedTitle = TextPatterns.guessTitleFromTables(doc.getTables());
        if (guessedTitle != null) {
            doc.setOriginalTitle(doc.getTitle());
            doc.setTitle(guessedTitle);
        }

        return doc;
    }

    private List<Picture> findPictures(ParaListCore paraListCore) {
        List<Picture> result = new ArrayList<>();
        if (paraListCore == null) return result;
        for (int i = 0; i < paraListCore.countOfPara(); i++) {
            Para p = paraListCore.getPara(i);
            for (int r = 0; r < p.countOfRun(); r++) {
                Run run = p.getRun(r);
                for (int it = 0; it < run.countOfRunItem(); it++) {
                    RunItem ri = run.getRunItem(it);
                    if (ri instanceof Picture) result.add((Picture) ri);
                }
            }
        }
        return result;
    }

    private ExtractedImage parseImage(Picture pic, Map<String, byte[]> binDataMap, int seq) {
        if (pic.img() == null) return null;
        String binId = pic.img().binaryItemIDRef();
        byte[] data = binDataMap.get(binId);
        if (data == null) return null;

        ExtractedImage img = new ExtractedImage(String.valueOf(seq), binId, data);
        if (pic.shapeComment() != null && pic.shapeComment().text() != null) {
            String comment = pic.shapeComment().text();
            String originalName = parseOriginalFilenameFromComment(comment);
            img.setOriginalFilename(originalName);
            img.setCaption(originalName);
        }
        return img;
    }

    private String parseOriginalFilenameFromComment(String comment) {
        // "그림입니다.\n원본 그림의 이름: xxx.jpg\n..." 형태에서 파일명 줄만 추출
        for (String line : comment.split("\n")) {
            int idx = line.indexOf("원본 그림의 이름:");
            if (idx >= 0) {
                return line.substring(idx + "원본 그림의 이름:".length()).trim();
            }
        }
        String[] lines = comment.split("\n");
        return lines.length > 0 ? lines[0].trim() : null;
    }

    private ExtractedTable parseTable(Table table, String tableId) {
        int rowCount = table.rowCnt();
        int colCount = table.colCnt();

        List<List<String>> grid = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < colCount; c++) row.add("");
            grid.add(row);
        }

        List<ExtractedCell> cells = new ArrayList<>();

        for (Tr tr : table.trs()) {
            for (Tc tc : tr.tcs()) {
                int colAddr = tc.cellAddr().colAddr();
                int rowAddr = tc.cellAddr().rowAddr();
                int colSpan = Math.max(1, tc.cellSpan().colSpan());
                int rowSpan = Math.max(1, tc.cellSpan().rowSpan());

                StringBuilder cellTextBuilder = new StringBuilder();
                SubList subList = tc.subList();
                if (subList != null) {
                    for (int i = 0; i < subList.countOfPara(); i++) {
                        String t = extractParaText(subList.getPara(i));
                        if (t != null && !t.trim().isEmpty()) {
                            if (cellTextBuilder.length() > 0) cellTextBuilder.append("\n");
                            cellTextBuilder.append(t.trim());
                        }
                    }
                }
                String cellText = cellTextBuilder.toString();

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

    /**
     * 문단 하나의 전체 텍스트를 반환한다 (표/그림 같은 RunItem은 건너뛰고 T만 처리).
     * T 내부의 모든 TItem 타입(NormalText/FWSpace/Tab/LineBreak/NBSpace 등)을
     * 명시적으로 처리해서 텍스트가 중간에 끊기지 않게 한다.
     */
    private String extractParaText(Para para) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < para.countOfRun(); r++) {
            Run run = para.getRun(r);
            for (int it = 0; it < run.countOfRunItem(); it++) {
                RunItem runItem = run.getRunItem(it);
                if (runItem instanceof T) {
                    sb.append(extractTText((T) runItem));
                }
            }
        }
        return sb.toString();
    }

    private String extractTText(T t) {
        if (t.isEmpty()) return "";
        if (t.isOnlyText()) return t.onlyText();

        StringBuilder sb = new StringBuilder();
        int count = t.countOfItems();
        for (int i = 0; i < count; i++) {
            TItem item = t.getItem(i);
            if (item instanceof NormalText) {
                String s = ((NormalText) item).text();
                if (s != null) sb.append(s);
            } else if (item instanceof Tab) {
                sb.append("\t");
            } else if (item instanceof LineBreak) {
                sb.append("\n");
            } else if (item instanceof FWSpace) {
                sb.append(" ");
            } else if (item instanceof NBSpace) {
                sb.append(" ");
            }
            // MarkpenBegin/End, TitleMark, InsertBegin/End, DeleteBegin/End 등은
            // 변경추적/서식 마커일 뿐 실제 텍스트가 아니므로 건너뜀
        }
        return sb.toString();
    }
}
