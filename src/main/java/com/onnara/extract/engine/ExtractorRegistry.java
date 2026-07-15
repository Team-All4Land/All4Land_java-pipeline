package com.onnara.extract.engine;

import com.onnara.extract.engine.hml.HmlExtractor;
import com.onnara.extract.engine.hwp.HwplibExtractor;
import com.onnara.extract.engine.hwpx.OwpmlExtractor;
import com.onnara.extract.engine.pdf.PdfBoxExtractor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 확장자 → 네이티브 Extractor 매핑.
 *
 * <p>CLI·pipeline은 이 레지스트리 기반으로 라우팅하므로,
 * 새 형식은 Extractor 구현 후 여기 등록만 하면 된다.
 */
public final class ExtractorRegistry {

    /** 등록된 엔진들 — forExtension이 앞에서부터 supports를 물어 첫 매칭을 쓴다. */
    private static final List<Extractor> EXTRACTORS = new ArrayList<>();

    /** 파이프라인이 입력 폴더에서 수집하는 확장자 목록. */
    private static final Set<String> EXTENSIONS =
            new LinkedHashSet<>(List.of("hwp", "hwpx", "hml", "pdf"));

    /** 기본 지원 엔진 4종을 등록한다. */
    static {
        register(new PdfBoxExtractor());
        register(new HwplibExtractor());
        register(new OwpmlExtractor());
        register(new HmlExtractor());
    }

    /** 인스턴스화 방지 — 정적 레지스트리 클래스. */
    private ExtractorRegistry() {
    }

    /** 엔진을 등록한다(새 형식 통합 시 호출). */
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

    /** 엔진 이름(hwplib/owpml/hml-dom/pdfbox)으로 강제 선택 (--engine 옵션). 없으면 예외. */
    public static Extractor forEngineName(String engineName) {
        for (Extractor e : EXTRACTORS) {
            if (e.engineName().equals(engineName)) {
                return e;
            }
        }
        throw new IllegalArgumentException("등록되지 않은 엔진입니다: " + engineName);
    }

    /** 지원 확장자 집합 (입력 폴더 재귀 스캔용). */
    public static Set<String> supportedExtensions() {
        return Set.copyOf(EXTENSIONS);
    }
}
