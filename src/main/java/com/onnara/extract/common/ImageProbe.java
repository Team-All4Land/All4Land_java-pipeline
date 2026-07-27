package com.onnara.extract.common;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * 이미지 파일의 픽셀 크기를 헤더만 읽어 알아낸다.
 *
 * <p>OCR 후보를 고를 때 쓴다 — 도장·로고·서명 같은 소형 이미지는 읽어 봐야 본문
 * 정보가 없고, 배치에서 VLM 추론 시간만 잡아먹는다. 크기 판단에 이미지를 통째로
 * 디코딩하면 사진 한 장에 수십 MB 힙이 필요하므로, {@link ImageReader}로 헤더만 본다.
 */
public final class ImageProbe {

    /** 인스턴스화 방지 — 정적 조회 함수만 제공하는 유틸리티 클래스. */
    private ImageProbe() {
    }

    /**
     * 이미지의 긴 변 픽셀 수를 반환한다. 형식을 못 읽으면 -1.
     *
     * <p>-1은 "판단 불가"이지 "작다"가 아니다 — 호출부는 크기를 못 재면
     * 후보에서 빼지 말고 그대로 두어야 정보 손실이 없다.
     */
    public static int maxDimension(Path image) {
        try (ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
            if (in == null) {
                return -1;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return -1;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return Math.max(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            // 깨진 이미지·미지원 형식 — 판단 불가로 돌려보내고 호출부가 결정한다
            return -1;
        }
    }
}
