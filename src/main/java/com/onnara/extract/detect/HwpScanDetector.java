package com.onnara.extract.detect;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlArc;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlContainer;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlCurve;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlEllipse;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPolygon;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlRectangle;
import kr.dogfoot.hwplib.object.bodytext.control.gso.GsoControl;
import kr.dogfoot.hwplib.object.bodytext.control.gso.caption.Caption;
import kr.dogfoot.hwplib.object.bodytext.control.gso.textbox.TextBox;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.ParagraphList;
import kr.dogfoot.hwplib.reader.HWPReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * 스캔본 HWP 판별 — 네이티브 본문 텍스트가 사실상 없을 때만 스캔본으로 본다.
 *
 * <p><b>이미지의 개수·크기·면적은 판정 근거가 아니다.</b> 사진이 지면 대부분을
 * 차지한다는 것만으로 스캔본이 되지 않는다 — 고시류에는 붙임 현장사진·위치도처럼
 * 본문이 사진으로 채워진 네이티브 문서가 흔하고, 그런 문서를 스캔본으로 넘기면
 * 네이티브로 뽑을 수 있었던 문단·표를 버리게 된다.
 *
 * <p>판정 기준은 하나다: <b>{@link #MIN_TEXT_CHARS}자 이상 본문 텍스트가 있으면
 * 네이티브</b>. 그 미만이면서 임베디드 이미지가 있으면 "본문이 이미지 안에 들어
 * 있다"고 보고 스캔본으로 판정한다(이미지가 아예 없으면 OCR로 얻을 것도 없으므로
 * 스캔본이 아니다).
 *
 * <p>그래서 이 판별의 정확도는 <b>텍스트를 빠짐없이 세는가</b>에 달려 있다.
 * hwplib은 컨테이너마다 문단 목록을 따로 들고 있어, 한 군데라도 빠뜨리면 본문이
 * 과소집계돼 곧바로 스캔 오판이 된다. 본문·표 셀(중첩 포함)·글상자·캡션을 모두
 * 훑는 이유다. 머리말/꼬리말·각주는 일부러 뺐다 — 진짜 스캔본에도 쪽번호나
 * 머리글이 남아 있어, 세면 반대로 스캔본을 네이티브로 오판한다.
 */
public class HwpScanDetector implements ScanDetector {

    /**
     * 본문 텍스트를 누적하다 임계치를 넘으면 네이티브(false)로 조기 판정하고,
     * 임계치 미만이면서 임베디드 BinData 이미지가 있으면 스캔본(true)으로 본다.
     */
    @Override
    public boolean isScanned(Path file) {
        try {
            // 잠긴 문서는 먼저 걸러 낸다 — 그대로 넘기면 hwplib이 암호문을 구조로 읽으려다
            // "This is not paragraph."로 죽어, 원인 체인에 암호 얘기가 남지 않는다
            EncryptionProbe.requireUnlocked(file);
            HWPFile hwpFile = HWPReader.fromFile(file.toString());
            int textLen = 0;
            for (Section section : hwpFile.getBodyText().getSectionList()) {
                textLen = accumulateText(section.getParagraphs(), textLen);
                if (textLen >= MIN_TEXT_CHARS) {
                    return false;
                }
            }
            int imageCount = hwpFile.getBinData() == null
                    ? 0
                    : hwpFile.getBinData().getEmbeddedBinaryDataList().size();
            return imageCount > 0;
        } catch (Exception e) {
            throw new UncheckedIOException("HWP 스캔 판별 실패: " + file,
                    e instanceof IOException io ? io : new IOException(e));
        }
    }

    /**
     * 문단과 그 안의 모든 텍스트 컨테이너를 훑어 글자 수를 누적한다(임계치 도달 시 조기 반환).
     *
     * <p>hwplib의 {@code section.getParagraphs()}에는 표 셀·글상자·캡션 문단이 들어 있지
     * 않다. 고시류는 본문 대부분이 표 안에 있고 사진 설명은 캡션이나 글상자에 붙는 일이
     * 잦아, 이 경로들을 빠뜨리면 텍스트가 멀쩡히 있는 문서가 "텍스트 없음"으로 집계된다.
     */
    private static int accumulateText(Paragraph[] paragraphs, int textLen) throws Exception {
        for (Paragraph paragraph : paragraphs) {
            String text = paragraph.getNormalString();
            if (text != null) {
                textLen += text.trim().length();
            }
            if (textLen >= MIN_TEXT_CHARS) {
                return textLen;
            }
            if (paragraph.getControlList() == null) {
                continue;
            }
            for (Control control : paragraph.getControlList()) {
                textLen = accumulateControlText(control, textLen);
                if (textLen >= MIN_TEXT_CHARS) {
                    return textLen;
                }
            }
        }
        return textLen;
    }

    /** 컨트롤 한 개가 품고 있는 텍스트(표 셀·글상자·캡션·묶음 자식)를 누적한다. */
    private static int accumulateControlText(Control control, int textLen) throws Exception {
        if (control.getType() == ControlType.Table) {
            for (Row row : ((ControlTable) control).getRowList()) {
                for (Cell cell : row.getCellList()) {
                    textLen = accumulateText(toArray(cell.getParagraphList()), textLen);
                    if (textLen >= MIN_TEXT_CHARS) {
                        return textLen;
                    }
                }
            }
            return textLen;
        }
        if (control instanceof GsoControl gso) {
            Caption caption = gso.getCaption();
            if (caption != null) {
                textLen = accumulateText(toArray(caption.getParagraphList()), textLen);
                if (textLen >= MIN_TEXT_CHARS) {
                    return textLen;
                }
            }
            TextBox textBox = textBoxOf(gso);
            if (textBox != null) {
                textLen = accumulateText(toArray(textBox.getParagraphList()), textLen);
                if (textLen >= MIN_TEXT_CHARS) {
                    return textLen;
                }
            }
            if (gso instanceof ControlContainer container) {
                for (GsoControl child : container.getChildControlList()) {
                    textLen = accumulateControlText(child, textLen);
                    if (textLen >= MIN_TEXT_CHARS) {
                        return textLen;
                    }
                }
            }
        }
        return textLen;
    }

    /**
     * 도형이 글상자를 품고 있으면 꺼낸다. 없으면 null.
     *
     * <p>hwplib에는 "글상자를 가진 도형"의 공통 인터페이스가 없어 타입을 나열한다
     * (그림·선·OLE 등 나머지는 글상자를 갖지 않는다).
     */
    private static TextBox textBoxOf(GsoControl gso) {
        if (gso instanceof ControlRectangle c) {
            return c.getTextBox();
        }
        if (gso instanceof ControlEllipse c) {
            return c.getTextBox();
        }
        if (gso instanceof ControlArc c) {
            return c.getTextBox();
        }
        if (gso instanceof ControlPolygon c) {
            return c.getTextBox();
        }
        if (gso instanceof ControlCurve c) {
            return c.getTextBox();
        }
        return null;
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
}
