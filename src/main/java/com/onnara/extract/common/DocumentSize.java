package com.onnara.extract.common;

import com.onnara.extract.common.model.RawContent;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;

import java.util.List;

/**
 * 문서의 본문 분량을 잰다 — "이 파일이 고시문인가, 안내문인가"를 가르는 데 쓴다.
 *
 * <p>공유수면 고시문은 한두 쪽짜리다. 반면 같은 폴더에 섞여 들어오는 전자문서 이용안내·업무편람류는
 * 수십 쪽이면서 고시 항목(기관·고시번호·점용 장소 등)을 담고 있지 않다. 이런 문서를 적재하면
 * 표준 컬럼은 비어 있고 {@code extras}만 부풀어 사전 보강 통계를 왜곡한다.
 *
 * <p>분량은 <b>글자 수</b>로 잰다. 쪽수는 형식마다 의미가 다르고(HWP/HML은 쪽 개념이 추출 결과에
 * 없다), 파일 크기는 이미지 용량에 좌우돼 본문 분량과 무관하다.
 */
public final class DocumentSize {

    /** 인스턴스화 방지 — 정적 측정 함수만 제공하는 유틸리티 클래스. */
    private DocumentSize() {
    }

    /**
     * 본문 글자 수 — 문단 텍스트·표 셀 텍스트·표와 그림의 캡션을 합쳐 공백을 뺀 길이다.
     *
     * <p>표 셀은 {@code grid}로 센다. 병합 셀이 덮인 칸마다 반복돼 실제보다 부풀지만,
     * 임계치가 만 단위라 판정이 뒤집힐 정도는 아니고 {@code cells}가 없는 엔진(PDF·OCR)에서도
     * 같은 방식으로 세어 형식 간 기준이 갈리지 않는다.
     */
    public static int bodyCharCnt(RawDocument raw) {
        if (raw == null || raw.getContent() == null) {
            return 0;
        }
        int total = 0;
        for (RawContent item : raw.getContent()) {
            if (item instanceof RawParagraph p) {
                total += lengthOf(p.getText());
            } else if (item instanceof RawTable t) {
                total += gridChars(t.getGrid());
                total += lengthOf(t.getCaption());
            }
        }
        // 그림 캡션도 본문이다. 캡션이 content 문단에서 빠졌으므로 여기서 세지 않으면
        // 사진 위주 고시문의 분량이 실제보다 줄어 적재 판정(LoadPolicy)이 달라진다
        if (raw.getImages() != null) {
            for (RawImage image : raw.getImages()) {
                total += lengthOf(image.getCaption());
            }
        }
        return total;
    }

    /** 격자 전체의 글자 수. */
    private static int gridChars(List<List<String>> grid) {
        if (grid == null) {
            return 0;
        }
        int total = 0;
        for (List<String> row : grid) {
            if (row == null) {
                continue;
            }
            for (String cell : row) {
                total += lengthOf(cell);
            }
        }
        return total;
    }

    /** 공백을 뺀 글자 수 — 서식용 공백·들여쓰기가 분량으로 잡히지 않게 한다. */
    private static int lengthOf(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }
}
