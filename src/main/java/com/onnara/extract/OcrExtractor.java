package com.onnara.extract;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;

/**
 * Tesseract 기반 OCR 모듈. hwp/hwpx/hml에서 추출한 이미지(스캔본을 사진 찍어
 * 붙여넣은 경우 등) 안의 텍스트를 인식한다.
 *
 * 사용 라이브러리: net.sourceforge.tess4j:tess4j (Apache-2.0, Tesseract JNA 래퍼)
 *
 * 왜 전처리가 필요한가: 실측 테스트 결과, 이미지 해상도가 낮으면 글자가 심하게
 * 누락/오인식되고 업스케일링하면 정확도가 크게 개선되는 것을 Python 버전에서
 * 확인했다. 여기서도 동일한 전처리(그레이스케일 + 업스케일링)를 적용한다.
 */
public final class OcrExtractor {

    private static final int MIN_DIMENSION_FOR_OCR = 1800;

    // Windows/Linux에서 흔히 쓰이는 기본 tessdata 경로. 환경에 맞게 조정 필요.
    private static final String[] DEFAULT_DATAPATHS = {
            "C:\\Program Files\\Tesseract-OCR\\tessdata",
            "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/4.00/tessdata",
            "/usr/share/tessdata",
    };

    private OcrExtractor() {
    }

    /**
     * 이미지 바이너리를 받아 OCR로 텍스트를 추출.
     *
     * @param data 이미지 바이너리
     * @param lang Tesseract 언어 코드 ('kor', 'kor+eng', 'eng' 등)
     * @return 인식된 텍스트 (실패/텍스트없음 시 빈 문자열)
     */
    public static String recognize(byte[] data, String lang) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) return "";

            BufferedImage preprocessed = preprocess(image);

            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath(findDatapath());
            tesseract.setLanguage(lang);
            tesseract.setPageSegMode(ITessAPI.TessPageSegMode.PSM_SINGLE_BLOCK);

            String text = tesseract.doOCR(preprocessed);
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            // 언어팩 미설치, 손상된 이미지 등 어떤 이유로든 실패하면 빈 결과로 안전하게 처리
            return "";
        }
    }

    private static String findDatapath() {
        for (String path : DEFAULT_DATAPATHS) {
            if (new File(path).isDirectory()) return path;
        }
        // 못 찾으면 TESSDATA_PREFIX 환경변수에 의존 (Tess4J/Tesseract 기본 동작)
        String env = System.getenv("TESSDATA_PREFIX");
        return env != null ? env : DEFAULT_DATAPATHS[0];
    }

    /** 그레이스케일 변환 + 저해상도면 업스케일링 (OCR 정확도가 해상도에 크게 좌우됨) */
    private static BufferedImage preprocess(BufferedImage src) {
        BufferedImage gray = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        gray.getGraphics().drawImage(src, 0, 0, null);

        int longestSide = Math.max(gray.getWidth(), gray.getHeight());
        if (longestSide >= MIN_DIMENSION_FOR_OCR) {
            return gray;
        }

        double scale = (double) MIN_DIMENSION_FOR_OCR / longestSide;
        int newWidth = (int) Math.round(gray.getWidth() * scale);
        int newHeight = (int) Math.round(gray.getHeight() * scale);

        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_BYTE_GRAY);
        AffineTransform at = AffineTransform.getScaleInstance(scale, scale);
        AffineTransformOp scaleOp = new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
        var g2d = scaled.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        scaleOp.filter(gray, scaled);
        g2d.dispose();

        return scaled;
    }
}
