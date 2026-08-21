package com.onnara.extract.detect;

import com.onnara.extract.engine.hwp3.Hwp3Document;
import com.onnara.extract.engine.hwp3.Hwp3Reader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * 스캔본 한글 3.0 판별 — 다른 형식과 같은 규칙이다: <b>본문 텍스트가
 * {@link #MIN_TEXT_CHARS}자 이상이면 네이티브</b>, 그 미만이면서 임베디드 이미지가 있으면 스캔본.
 *
 * <p>{@link Hwp3Reader}는 문단·표 셀(중첩 포함)·글상자·캡션을 모두 본문으로 담고
 * 머리말·꼬리말·각주는 애초에 담지 않는다. 그래서 여기서는 {@link Hwp3Document#bodyCharCnt()}를
 * 그대로 쓰면 {@link ScanDetector}의 "본문만 빠짐없이 센다" 요구가 충족된다.
 *
 * <p>파서를 한 번만 돌려 글자 수와 이미지 개수를 함께 얻는다 — 다른 판별기처럼 임계치를
 * 넘는 순간 조기 반환하지는 않지만, 한글 3.0 파일은 전량 배치에서도 문서가 작고
 * 파싱이 단일 패스라 두 번 여는 비용을 아끼는 편이 낫다.
 */
public class Hwp3ScanDetector implements ScanDetector {

    /** 본문이 임계치 미만이면서 임베디드 이미지가 있으면 스캔본(true). */
    @Override
    public boolean isScanned(Path file) {
        try {
            Hwp3Document doc = Hwp3Reader.read(file);
            if (doc.bodyCharCnt() >= MIN_TEXT_CHARS) {
                return false;
            }
            return !doc.images().isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException("한글 3.0 스캔 판별 실패: " + file, e);
        } catch (Exception e) {
            throw new UncheckedIOException("한글 3.0 스캔 판별 실패: " + file, new IOException(e));
        }
    }
}
