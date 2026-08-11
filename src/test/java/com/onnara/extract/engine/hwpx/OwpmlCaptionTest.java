package com.onnara.extract.engine.hwpx;

import com.onnara.extract.TestFixtures;
import com.onnara.extract.common.model.RawContent;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawTable;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.RunItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * HWPX 표 캡션 추출 검증.
 *
 * <p>{@code samples/}의 실물 HWPX에는 {@code <hp:caption>}이 하나도 없다. 그래서 실물만으로는
 * 이 경로가 <b>돌아가는지조차 확인되지 않는다.</b> 실물 문서를 hwpxlib으로 읽어 캡션을 달고
 * 다시 써서 픽스처를 만든다 — 손으로 XML을 짜는 것과 달리 <b>hwpxlib이 규격을 보증</b>하므로,
 * 테스트가 우리 가정이 아니라 실제 형식을 검증하게 된다.
 */
class OwpmlCaptionTest {

    @TempDir
    Path tempDir;

    private final OwpmlExtractor extractor = new OwpmlExtractor();

    /** 표에 달린 캡션이 문단이 아니라 표의 전용 필드로 들어와야 한다. */
    @Test
    void readsTableCaption() throws Exception {
        Path fixture = withTableCaption("<표 1> 공유수면 점용·사용허가 내역");

        RawDocument raw = extractor.extractRaw(fixture);

        assertEquals("<표 1> 공유수면 점용·사용허가 내역", firstTable(raw).getCaption());
    }

    /** 캡션이 없는 실물 문서는 null이어야 한다 — 없는 캡션을 지어내지 않는다. */
    @Test
    void leavesCaptionNullWhenThereIsNone() throws Exception {
        RawDocument raw = extractor.extractRaw(TestFixtures.sample("방치선박 제거공고(군산-2).hwpx"));

        assertNull(firstTable(raw).getCaption());
    }

    /** 실물 HWPX의 첫 표에 캡션을 달아 새 파일로 저장한다. */
    private Path withTableCaption(String text) throws Exception {
        Path source = TestFixtures.sample("방치선박 제거공고(군산-2).hwpx");
        HWPXFile hwpx = HWPXReader.fromFilepath(source.toString());

        Table table = findFirstTable(hwpx);
        table.createCaption();
        table.caption().createSubList();
        Para para = table.caption().subList().addNewPara();
        para.addNewRun().addNewT().addText(text);

        Path out = tempDir.resolve("표캡션.hwpx");
        Files.write(out, HWPXWriter.toBytes(hwpx));
        return out;
    }

    /** 문서에서 처음 나오는 표를 찾는다. */
    private static Table findFirstTable(HWPXFile hwpx) {
        for (int s = 0; s < hwpx.sectionXMLFileList().count(); s++) {
            SectionXMLFile section = hwpx.sectionXMLFileList().get(s);
            for (int p = 0; p < section.countOfPara(); p++) {
                Para para = section.getPara(p);
                for (int r = 0; r < para.countOfRun(); r++) {
                    Run run = para.getRun(r);
                    for (int i = 0; i < run.countOfRunItem(); i++) {
                        RunItem item = run.getRunItem(i);
                        if (item instanceof Table table) {
                            return table;
                        }
                    }
                }
            }
        }
        throw new AssertionError("픽스처에 표가 없습니다");
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
}
