package com.onnara.extract.engine;

import com.onnara.extract.detect.DocFormat;
import com.onnara.extract.engine.hml.HmlExtractor;
import com.onnara.extract.engine.hwp.HwplibExtractor;
import com.onnara.extract.engine.hwp3.Hwp3Extractor;
import com.onnara.extract.engine.hwpx.OwpmlExtractor;
import com.onnara.extract.engine.pdf.PdfBoxExtractor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 형식 키 → 네이티브 Extractor 매핑.
 *
 * <p>CLI·pipeline은 이 레지스트리 기반으로 라우팅하므로,
 * 새 형식은 Extractor 구현 후 여기 등록만 하면 된다.
 *
 * <p>라우팅은 {@link #forFile}(파일 <b>내용</b> 기반)을 쓴다. {@link #forExtension}은
 * 형식 키를 이미 아는 호출부({@code --engine} 강제 지정, 테스트)만 쓴다.
 */
public final class ExtractorRegistry {

    /** 등록된 엔진들 — forExtension이 앞에서부터 supports를 물어 첫 매칭을 쓴다. */
    private static final List<Extractor> EXTRACTORS = new ArrayList<>();

    /**
     * 파이프라인이 입력 폴더에서 수집하는 <b>확장자</b> 목록.
     *
     * <p>형식 키가 아니라 확장자다 — 한글 3.0 파일도 파일명은 {@code .hwp}라
     * 수집 단계에는 {@code hwp3}를 넣을 이유가 없다.
     */
    private static final Set<String> EXTENSIONS =
            new LinkedHashSet<>(List.of("hwp", "hwpx", "hml", "pdf"));

    /** 기본 지원 엔진 5종을 등록한다. */
    static {
        register(new PdfBoxExtractor());
        register(new HwplibExtractor());
        register(new Hwp3Extractor());
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

    /**
     * 파일 <b>내용</b>으로 판정한 형식에 맞는 엔진을 찾는다 — pipeline·extract의 기본 라우팅.
     *
     * <p>확장자만 보면 HWPX를 {@code .hwp}로 저장한 파일이 hwplib으로 가 "OLE 복합문서가
     * 아니다"로 실패한다. 읽을 수 있는 문서를 형식 판정 때문에 버리지 않으려면 내용이 근거여야 한다.
     */
    public static Extractor forFile(Path file) {
        return forExtension(DocFormat.routingKey(file));
    }

    /** 형식 키(소문자, 점 제외)에 맞는 엔진을 찾는다. 없으면 예외. */
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
