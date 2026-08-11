package com.onnara.extract.engine.hwpx;

import com.onnara.extract.common.ImageFormats;
import com.onnara.extract.detect.EncryptionProbe;
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
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.shapeobject.Caption;
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
        ParseResult result = parse(file);
        return result.raw;
    }

    /** manifest에서 복원한 이미지들을 outDir에 쓰고 저장 경로 목록을 반환한다. */
    @Override
    public List<Path> saveImages(Path file, Path outDir) throws IOException {
        ParseResult result = parse(file);
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
    private ParseResult parse(Path file) throws IOException {
        // 잠긴 문서는 먼저 걸러 낸다 — 본문 XML이 암호문이라 hwpxlib은
        // "Invalid byte 1 of 1-byte UTF-8 sequence"로 죽고, 그러면 손상으로 오진된다
        EncryptionProbe.requireUnlocked(file);
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
        // href("BinData/image1.bmp")·mediaType("image/png")은 매직바이트 판별이 실패했을 때만 쓸
        // 확장자 힌트로 함께 들고 간다 — 이게 없으면 wmf/emf 같은 형식이 전부 .bin으로 떨어진다
        Map<String, byte[]> binDataMap = new HashMap<>();
        Map<String, String> hintMap = new HashMap<>();
        for (ManifestItem item : hwpxFile.contentHPFFile().manifest().items()) {
            if (item.hasAttachedFile() && item.attachedFile() != null) {
                binDataMap.put(item.id(), item.attachedFile().data());
                hintMap.put(item.id(), item.href() != null ? item.href() : item.mediaType());
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
                                        addImage(images, pic, binDataMap, hintMap, stem);
                                    }
                                }
                            }
                        } else if (runItem instanceof Picture picture) {
                            addImage(images, picture, binDataMap, hintMap, stem);
                        }
                    }
                }
            }
        }

        for (ImageEntry entry : images) {
            RawImage image = new RawImage(entry.name, entry.data.length);
            image.setCaption(entry.caption);
            raw.getImages().add(image);
        }
        return new ParseResult(raw, images);
    }

    /**
     * Picture가 참조하는 manifest 바이너리(binaryItemIDRef)를 찾아 이미지 목록에 추가한다.
     *
     * <p>정보가 없는 이미지(흰 바탕에 표식 하나 등)와 도장은 {@link ImageSieve}가 걸러낸다.
     */
    private static void addImage(List<ImageEntry> images, Picture pic,
                                 Map<String, byte[]> binDataMap, Map<String, String> hintMap,
                                 String stem) {
        if (pic.img() == null) {
            return;
        }
        String id = pic.img().binaryItemIDRef();
        byte[] data = binDataMap.get(id);
        if (data == null || data.length == 0) {
            System.err.println("[경고] 그림이 참조하는 BinData를 찾지 못해 건너뜁니다: " + id);
            return;
        }
        if (!ImageSieve.accept(data)) {
            return;
        }
        String ext = ImageFormats.extensionFor(data, hintMap.get(id));
        String name = stem + "_img" + images.size() + "." + ext;
        if (ImageFormats.isUnknown(ext)) {
            System.err.println("[경고] 알 수 없는 이미지 형식이라 " + name + "으로 저장합니다"
                    + " (매직 " + ImageFormats.magicOf(data) + ", 힌트 " + hintMap.get(id) + ")");
        }
        images.add(new ImageEntry(name, data, captionTextOf(pic.caption())));
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
        RawTable raw = new RawTable(rowCount, colCount, cells, grid);
        raw.setCaption(captionTextOf(table.caption()));
        return raw;
    }

    /**
     * 캡션의 문단들을 한 줄 텍스트로 — 비어 있으면 null.
     *
     * <p>본문 문단과 같은 {@link #extractParaText}를 쓴다. 캡션에도 인라인 태그가 섞이므로,
     * 여기서만 다른 경로를 쓰면 "관리청 → 관" 잘림이 캡션에서 되살아난다.
     */
    private static String captionTextOf(Caption caption) {
        if (caption == null || caption.subList() == null) {
            return null;
        }
        SubList subList = caption.subList();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < subList.countOfPara(); i++) {
            String text = extractParaText(subList.getPara(i));
            if (text != null && !text.trim().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(text.trim());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
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

    /** 수집된 이미지 1건: 저장 파일명·원본 바이트·그림에 달린 캡션(없으면 null). */
    private record ImageEntry(String name, byte[] data, String caption) {
    }

    /** 단일 파싱 패스 결과: raw 문서 + 순서가 고정된 이미지 목록. */
    private record ParseResult(RawDocument raw, List<ImageEntry> images) {
    }
}
