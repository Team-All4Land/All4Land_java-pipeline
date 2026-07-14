package com.onnara.extract.ocr;

import net.sourceforge.tess4j.Tesseract;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 임베디드 이미지 OCR — Tess4J(Tesseract) 래핑.
 *
 * <p>{@code --ocr} 옵션이 켜졌을 때만 사용된다. 네이티브 Tesseract가 설치되지
 * 않은 환경에서도 배치 전체가 실패하지 않도록, 모든 오류를 흡수하고 null을
 * 반환한다({@link #isAvailable()}로 사전 확인 가능).
 */
public final class TesseractOcr {

    private final Tesseract tesseract;
    private volatile Boolean available;

    public TesseractOcr() {
        this(System.getenv("TESSDATA_PREFIX"), "kor+eng");
    }

    public TesseractOcr(String dataPath, String language) {
        this.tesseract = new Tesseract();
        if (dataPath != null && !dataPath.isBlank()) {
            tesseract.setDatapath(dataPath);
        }
        tesseract.setLanguage(language == null || language.isBlank() ? "kor+eng" : language);
    }

    /** 네이티브 Tesseract 사용 가능 여부를 1회만 프로브해 캐시한다. */
    public boolean isAvailable() {
        if (available == null) {
            synchronized (this) {
                if (available == null) {
                    available = probe();
                }
            }
        }
        return available;
    }

    private boolean probe() {
        try {
            BufferedImage tiny = new BufferedImage(10, 10, BufferedImage.TYPE_BYTE_GRAY);
            tesseract.doOCR(tiny);
            return true;
        } catch (Throwable t) {
            System.err.println("[경고] Tesseract를 사용할 수 없습니다(--ocr 무시됨): " + t.getMessage());
            return false;
        }
    }

    public String recognize(Path imageFile) {
        try {
            return recognize(Files.readAllBytes(imageFile));
        } catch (IOException e) {
            System.err.println("[경고] OCR 대상 이미지를 읽을 수 없습니다: " + imageFile);
            return null;
        }
    }

    public String recognize(byte[] imageBytes) {
        if (!isAvailable()) {
            return null;
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (src == null) {
                return null;
            }
            BufferedImage prepared = preprocess(src);
            String text = tesseract.doOCR(prepared);
            return (text == null || text.isBlank()) ? null : text.trim();
        } catch (Throwable t) {
            System.err.println("[경고] OCR 인식 실패: " + t.getMessage());
            return null;
        }
    }

    /** 그레이스케일 → (작은 이미지면) 2배 업스케일 → 대비 보정. */
    private static BufferedImage preprocess(BufferedImage src) {
        BufferedImage gray = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        gray.getGraphics().drawImage(src, 0, 0, null);

        BufferedImage scaled = gray;
        if (Math.max(gray.getWidth(), gray.getHeight()) < 1000) {
            int w = gray.getWidth() * 2;
            int h = gray.getHeight() * 2;
            scaled = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            scaled.getGraphics().drawImage(
                    gray.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
        }

        RescaleOp contrast = new RescaleOp(1.2f, 10f, null);
        return contrast.filter(scaled, null);
    }
}
