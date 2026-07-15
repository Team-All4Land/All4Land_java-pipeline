package com.onnara.extract.detect;

import com.onnara.extract.TestFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 형식별 스캔 판별기와 DetectorRegistry 라우팅의 samples/ 기반 회귀 테스트. */
class DetectorsTest {

    /** 네이티브 HWP 샘플이 스캔본으로 오탐되지 않는지 검증한다. */
    @Test
    void nativeHwpSamplesAreNotScanned() {
        assertFalse(new HwpScanDetector().isScanned(
                TestFixtures.sample("공유수면 점용사용 허가 고시문.hwp")));
        assertFalse(new HwpScanDetector().isScanned(
                TestFixtures.sample("공유수면 점용사용 승인사항 고시.hwp")));
    }

    /** 네이티브 HWPX 샘플이 스캔본으로 오탐되지 않는지 검증한다. */
    @Test
    void nativeHwpxSamplesAreNotScanned() {
        assertFalse(new HwpxScanDetector().isScanned(
                TestFixtures.sample("방치선박 제거공고(군산-2).hwpx")));
        assertFalse(new HwpxScanDetector().isScanned(
                TestFixtures.sample("방치선박 제거공고(부안-11).hwpx")));
    }

    /** 네이티브 HML 샘플이 스캔본으로 오탐되지 않는지 검증한다. */
    @Test
    void nativeHmlSamplesAreNotScanned() {
        assertFalse(new HmlScanDetector().isScanned(
                TestFixtures.sample("8_공유수면 점용 사용 변경허가 고시(한국해양소년단).hml")));
        assertFalse(new HmlScanDetector().isScanned(
                TestFixtures.sample(
                        "1_공유수면 점용·사용 실시계획 준공검사확인증 발급 고시(인천시종합건설본부, 영종도 해안순환도로).hml")));
    }

    /** 본문 텍스트가 없고 임베디드 이미지(BinData/image1.bmp) 1개뿐인 스캔 후보. */
    @Test
    void ocrCandidateHwpxIsScanned() {
        Path file = TestFixtures.sample("ocr 테스트용.hwpx");
        assertTrue(new HwpxScanDetector().isScanned(file));
    }

    /** DetectorRegistry가 확장자에 맞는 판별기로 라우팅하는지 검증한다. */
    @Test
    void registryRoutesByExtension() {
        assertFalse(DetectorRegistry.isScanned(
                TestFixtures.sample("공유수면 점용사용 허가 고시문.hwp")));
        assertTrue(DetectorRegistry.isScanned(
                TestFixtures.sample("ocr 테스트용.hwpx")));
    }
}
