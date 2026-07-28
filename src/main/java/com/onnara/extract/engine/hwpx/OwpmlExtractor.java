package com.onnara.extract.engine.hwpx;

import com.onnara.extract.common.ImageFormats;
import com.onnara.extract.common.model.RawCell;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;
import com.onnara.extract.engine.Extractor;
import com.onnara.extract.engine.image.ImageSieve;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HWPX(OWPML, ZIP+XML) 네이티브 추출 엔진 — hwpxlib 기반.
 *
 * <p>핵심: T 내부의 모든 TItem 타입(NormalText/Tab/LineBreak/FWSpace/NBSpace)을
 * 명시적으로 처리한다. NormalText만 읽으면 인라인 태그(&lt;hp:fwSpace/&gt; 등)에서
 * 텍스트가 끊기는 "관리청 → 관" 잘림 버그가 재현된다 — {@link #extractTText}.
 */
public class OwpmlExtractor implements Extractor {

    /** {@code hwpx} 확장자만 지원한다. */
    @Override
    public boolean supports(String ext) {
        return "hwpx".equals(ext);
    }

    /** 엔진 식별자 "owpml". */
    @Override
    public String engineName() {
        return "owpml";
    }

    /** 파일을 파싱해 raw 문서만 반환한다(이미지는 메타로만 포함). */
    @Override
    public RawDocument extractRaw(Path file) throws IOException {
        // 제외 로그는 항상 실행되는 이 경로에서만 남긴다(saveImages와 중복 방지)
        ParseResult result = parse(file, true);
        return result.raw;
    }

    /** manifest에서 복원한 이미지들을 outDir에 쓰고 저장 경로 목록을 반환한다. */
    @Override
    public List<Path> saveImages(Path file, Path outDir) throws IOException {
        ParseResult result = parse(file, false);
        List<Path> saved = new ArrayList<>();
        for (ImageEntry entry : result.images) {
            Files.createDirectories(outDir);
            Path dest = outDir.resolve(entry.name);
            Files.write(dest, entry.data);
            saved.add(dest);
        }
        return saved;
    }

    /** 단일 파싱 패스 — extractRaw/saveImages의 이미지 순서·이름 일치를 보장한다. */
    private ParseResult parse(Path file, boolean log) throws IOException {
        HWPXFile hwpxFile;
        try {
            hwpxFile = HWPXReader.fromFilepath(file.toString());
        } catch (Exception e) {
            throw new IOException("HWPX 파일을 읽을 수 없습니다: " + file, e);
        }

        RawDocument raw = new RawDocument(file.getFileName().toString(), "hwpx", false);
        List<ImageEntry> images = new ArrayList<>();
        String stem = stemOf(file);

        // 이미지 원본: content.hpf manifest의 attachedFile에서 id → bytes
        Map<String, byte[]> binDataMap = new HashMap<>();
        for (ManifestItem item : hwpxFile.contentHPFFile().manifest().items()) {
            if (item.hasAttachedFile() && item.attachedFile() != null) {
                binDataMap.put(item.id(), item.attachedFile().data());
            }
        }

        for (int secIdx = 0; secIdx < hwpxFile.sectionXMLFileList().count(); secIdx++) {
            SectionXMLFile section = hwpxFile.sectionXMLFileList().get(secIdx);
            for (int idx = 0; idx < section.countOfPara(); idx++) {
                Para para = section.getPara(idx);

                String text = extractParaText(para);
                if (text != null && !text.trim().isEmpty()) {
                    raw.getContent().add(new RawParagraph(text.trim()));
                }

                for (int r = 0; r < para.countOfRun(); r++) {
                    Run run = para.getRun(r);
                    for (int it = 0; it < run.countOfRunItem(); it++) {
                        RunItem runItem = run.getRunItem(it);
                        if (runItem instanceof Table table) {
                            raw.getContent().add(parseTable(table));
                            // 표 셀 안에 들어있는 그림까지 수집
                            for (Tr tr : table.trs()) {
                                for (Tc tc : tr.tcs()) {
                                    for (Picture pic : findPictures(tc.subList())) {
                                        addImage(images, pic, binDataMap, stem, log);
                                    }
                                }
                            }
                        } else if (runItem instanceof Picture picture) {
                            addImage(images, picture, binDataMap, stem, log);
                        }
                    }
                }
            }
        }

        for (ImageEntry entry : images) {
            raw.getImages().add(new RawImage(entry.name, entry.data.length));
        }
        return new ParseResult(raw, images);
    }

    /**
     * Picture가 참조하는 manifest 바이너리(binaryItemIDRef)를 찾아 이미지 목록에 추가한다.
     *
     * <p>정보가 없는 이미지(흰 바탕에 표식 하나 등)와 도장은 {@link ImageSieve}가 걸러낸다.
     */
    private static void addImage(List<ImageEntry> images, Picture pic,
                                 Map<String, byte[]> binDataMap, String stem, boolean log) {
        if (pic.img() == null) {
            return;
        }
        byte[] data = binDataMap.get(pic.img().binaryItemIDRef());
        if (data == null || data.length == 0) {
            return;
        }
        String reason = ImageSieve.reject(data);
        if (reason != null) {
            if (log) {
                System.err.println("[이미지 제외] " + stem + ": " + reason);
            }
            return;
        }
        images.add(new ImageEntry(
                stem + "_img" + images.size() + "." + ImageFormats.extensionFor(data), data));
    }

    /** 셀 하위 문단 구조(ParaListCore)에서 Picture RunItem을 모두 찾아 반환한다. */
    private static List<Picture> findPictures(ParaListCore paraListCore) {
        List<Picture> result = new ArrayList<>();
        if (paraListCore == null) {
            return result;
        }
        for (int i = 0; i < paraListCore.countOfPara(); i++) {
            Para p = paraListCore.getPara(i);
            for (int r = 0; r < p.countOfRun(); r++) {
                Run run = p.getRun(r);
                for (int it = 0; it < run.countOfRunItem(); it++) {
                    RunItem ri = run.getRunItem(it);
                    if (ri instanceof Picture picture) {
                        result.add(picture);
                    }
                }
            }
        }
        return result;
    }

    /**
     * hwpxlib Table을 raw 표 모델로 변환한다. Tc의 주소·병합 span과 셀 문단 텍스트를
     * 읽어 cells를 채우고, 병합 범위를 grid에 복제한다.
     */
    private static RawTable parseTable(Table table) {
        int rowCount = table.rowCnt();
        int colCount = table.colCnt();

        List<List<String>> grid = new ArrayList<>(rowCount);
        for (int r = 0; r < rowCount; r++) {
            List<String> row = new ArrayList<>(colCount);
            for (int c = 0; c < colCount; c++) {
                row.add("");
            }
            grid.add(row);
        }

        List<RawCell> cells = new ArrayList<>();
        for (Tr tr : table.trs()) {
            for (Tc tc : tr.tcs()) {
                int colAddr = tc.cellAddr().colAddr();
                int rowAddr = tc.cellAddr().rowAddr();
                int colSpan = Math.max(1, tc.cellSpan().colSpan());
                int rowSpan = Math.max(1, tc.cellSpan().rowSpan());

                StringBuilder cellText = new StringBuilder();
                SubList subList = tc.subList();
                if (subList != null) {
                    for (int i = 0; i < subList.countOfPara(); i++) {
                        String t = extractParaText(subList.getPara(i));
                        if (t != null && !t.trim().isEmpty()) {
                            if (cellText.length() > 0) {
                                cellText.append("\n");
                            }
                            cellText.append(t.trim());
                        }
                    }
                }
                String text = cellText.toString();

                cells.add(new RawCell(rowAddr, colAddr, rowSpan, colSpan, text));
                for (int r = rowAddr; r < Math.min(rowAddr + rowSpan, rowCount); r++) {
                    for (int c = colAddr; c < Math.min(colAddr + colSpan, colCount); c++) {
                        grid.get(r).set(c, text);
                    }
                }
            }
        }
        return new RawTable(rowCount, colCount, cells, grid);
    }

    /** 문단 하나의 전체 텍스트 (표/그림 RunItem은 건너뛰고 T만 처리). */
    private static String extractParaText(Para para) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < para.countOfRun(); r++) {
            Run run = para.getRun(r);
            for (int it = 0; it < run.countOfRunItem(); it++) {
                RunItem runItem = run.getRunItem(it);
                if (runItem instanceof T t) {
                    sb.append(extractTText(t));
                }
            }
        }
        return sb.toString();
    }

    /**
     * T 내부의 모든 TItem 타입을 명시적으로 처리한다. NormalText만 읽으면
     * 인라인 태그 지점에서 텍스트가 끊긴다(레거시 hwp_hwpx_parser의 "관리청 → 관" 버그).
     */
    private static String extractTText(T t) {
        if (t.isEmpty()) {
            return "";
        }
        if (t.isOnlyText()) {
            return t.onlyText();
        }
        StringBuilder sb = new StringBuilder();
        int count = t.countOfItems();
        for (int i = 0; i < count; i++) {
            TItem item = t.getItem(i);
            if (item instanceof NormalText normalText) {
                String s = normalText.text();
                if (s != null) {
                    sb.append(s);
                }
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

    /** 확장자를 제외한 파일명(이미지 파일명 접두사로 사용). */
    private static String stemOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** 수집된 이미지 1건: 저장 파일명과 원본 바이트. */
    private record ImageEntry(String name, byte[] data) {
    }

    /** 단일 파싱 패스 결과: raw 문서 + 순서가 고정된 이미지 목록. */
    private record ParseResult(RawDocument raw, List<ImageEntry> images) {
    }
}
