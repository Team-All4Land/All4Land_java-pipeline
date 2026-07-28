package com.onnara.extract.detect;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 스캔본 PDF 판별 — 다른 형식과 같은 한 줄 규칙을 <b>첫 페이지에</b> 적용한다.
 *
 * <pre>
 * 스캔본 = 첫 페이지 텍스트가 한 글자도 없다 AND 첫 페이지에 이미지가 1개 이상
 * </pre>
 *
 * <p>임계치는 {@link ScanDetector#MIN_TEXT_CHARS}(한 글자)를 그대로 쓴다 — 텍스트 레이어로
 * 한 글자라도 뽑힌다면 그 페이지는 스캔 이미지가 아니라는 증거다. 스캐너가 심어 놓은 OCR
 * 텍스트 레이어가 있는 PDF도 이 규칙에서 '네이티브'가 된다(기존 파이프라인이 그 텍스트를
 * 그대로 읽을 수 있으므로 정상 동작).
 *
 * <p><b>왜 첫 페이지만 보는가.</b> 예전에는 전 페이지의 텍스트 레이어 유무를 세어 텍스트
 * 페이지 비율이 절반 미만이면 스캔본으로 봤다. 그 기준은 사실상 지면 점유율 판정이어서,
 * {@link ScanDetector}가 실측까지 들어 배제한 신호를 되살린 것과 같았다 — 붙임 위치도·현장사진이
 * 페이지 절반을 넘는 네이티브 고시 PDF가 스캔본으로 오판돼, 라우팅이 문서 단위 전부/전무인
 * 탓에 네이티브로 뽑을 수 있었던 문단·표를 통째로 버렸다. 첫 페이지 하나로 판정하면 그 오판
 * 경로가 닫히고, 판별 비용도 문서 크기와 무관해진다.
 *
 * <p>이미지 유무를 함께 요구하는 이유는 다른 형식과 같다 — 텍스트도 이미지도 없는 첫 페이지는
 * 빈 문서이지 스캔본이 아니고, OCR로 얻을 것도 없다.
 *
 * <p><b>감수하는 대가.</b>
 * <ul>
 *   <li>혼합 문서에서 첫 장이 네이티브면 뒤쪽 스캔 페이지의 본문은 버려진다. 반대로 첫 장이
 *       스캔이면 뒤쪽 네이티브 페이지도 전부 OCR로 간다 — OCR 계약(§7)이 문서 단위 라우팅이라
 *       페이지별로 섞어 처리할 수 없다. 앞의 경우는 {@code PdfBoxExtractor}가 텍스트 없는
 *       페이지마다 남기는 경고로 로그에 드러난다.</li>
 *   <li>스캔 페이지에 쪽번호·전자문서 스탬프 같은 텍스트 레이어가 한 글자라도 있으면
 *       네이티브로 판정된다. {@link PDFTextStripper}는 페이지 텍스트를 평면화해서
 *       머리말·꼬리말을 구조적으로 구분할 수 없어, HWP 계열 판별기처럼 제외할 수 없다.</li>
 * </ul>
 */
public class PdfScanDetector implements ScanDetector {

    /**
     * 첫 페이지가 스캔 이미지뿐인지 판정한다.
     *
     * <p>첫 페이지 텍스트가 {@link #MIN_TEXT_CHARS}자 이상이면 네이티브(false)로 확정하고,
     * 그 미만이면서 첫 페이지에 이미지가 있으면 스캔본(true)으로 본다. 페이지가 없으면 false.
     */
    @Override
    public boolean isScanned(Path file) {
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            if (doc.getNumberOfPages() == 0) {
                return false;
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            if (stripper.getText(doc).trim().length() >= MIN_TEXT_CHARS) {
                return false;
            }
            return hasImage(doc.getPage(0).getResources(),
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        } catch (IOException e) {
            throw new UncheckedIOException("PDF 스캔 판별 실패: " + file, e);
        }
    }

    /**
     * 리소스(중첩 폼 XObject 포함)에 이미지가 하나라도 있으면 true — 첫 이미지에서 곧바로 끝낸다.
     *
     * <p>스캔 이미지가 폼 XObject로 한 겹 감싸여 있는 PDF가 실재하므로 재귀로 내려간다.
     * 폼끼리 서로를 참조하는 문서에서 무한 재귀에 빠지지 않도록 {@code seen}으로 방문한
     * XObject를 기억한다(PDFBox는 순환을 막아주지 않는다).
     *
     * <p>{@code PdfBoxExtractor.imagesOf}가 같은 순회를 하지만 그쪽은 {@code private}이고 다른
     * 패키지에 있으며 이미지 <b>목록</b>을 만든다. 판별기는 "하나라도 있나"만 필요해 단축 평가가
     * 맞고, 노출하면 지금은 없는 {@code detect → engine.pdf} 결합이 생겨 의도적으로 중복한다.
     */
    private static boolean hasImage(PDResources resources, Set<COSBase> seen) throws IOException {
        if (resources == null) {
            return false;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobj = resources.getXObject(name);
            if (xobj instanceof PDImageXObject) {
                return true;
            }
            if (xobj instanceof PDFormXObject form && seen.add(xobj.getCOSObject())
                    && hasImage(form.getResources(), seen)) {
                return true;
            }
        }
        return false;
    }
}
