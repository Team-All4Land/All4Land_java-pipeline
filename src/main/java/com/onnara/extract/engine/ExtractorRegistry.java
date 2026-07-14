package com.onnara.extract.engine;

import com.onnara.extract.engine.pdf.PdfBoxExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 확장자 → 네이티브 Extractor 매핑.
 *
 * <p>CLI·pipeline은 이 레지스트리 기반으로 라우팅하므로,
 * 새 형식은 Extractor 구현 후 여기 등록만 하면 된다.
 */
public final class ExtractorRegistry {

    private static final List<Extractor> EXTRACTORS = new ArrayList<>();

    static {
        register(new PdfBoxExtractor());
        // 이후 단계: HwplibExtractor / OwpmlExtractor / HmlExtractor 등록
    }

    private ExtractorRegistry() {
    }

    public static void register(Extractor extractor) {
        EXTRACTORS.add(extractor);
    }

    /** 확장자(소문자, 점 제외)에 맞는 엔진을 찾는다. 없으면 예외. */
    public static Extractor forExtension(String ext) {
        String lower = ext.toLowerCase(Locale.ROOT);
        for (Extractor e : EXTRACTORS) {
            if (e.supports(lower)) {
                return e;
            }
        }
        throw new IllegalArgumentException("지원하지 않는 확장자입니다: " + ext);
    }
}