package com.onnara.extract.engine.hwp3;

import java.util.List;

/**
 * {@link Hwp3Reader}가 한글 3.0 파일에서 읽어 낸 결과 — 본문 항목과 임베디드 이미지.
 *
 * <p>{@code items}는 <b>문서 등장 순서</b>다. 한글 3.0은 표·그림을 문단의 글자 스트림 안에
 * 제어 문자로 끼워 넣으므로, 한 문단이 "앞 텍스트 → 표 → 뒤 텍스트"로 쪼개질 수 있다.
 * 그 순서를 그대로 평평하게 편 목록이라 {@code RawDocument.content}에 1:1로 옮겨진다.
 *
 * <p>머리말·꼬리말·각주 문단은 여기 담기지 않는다. 커서를 진행시키기 위해 파싱은 하지만,
 * 본문이 아니어서 버린다({@link com.onnara.extract.detect.ScanDetector} 계약 — 진짜 스캔본에도
 * 머리글·쪽번호는 남아 있어, 본문으로 세면 스캔 판별이 반대로 뒤집힌다).
 */
public record Hwp3Document(List<Item> items, List<Image> images) {

    /** 본문 항목 — 문단 텍스트이거나 표다. */
    public sealed interface Item permits Text, Table {
    }

    /**
     * 문단 텍스트 한 덩어리.
     *
     * <p><b>그림</b>의 캡션도 이 형태로 담긴다. 한글 3.0은 임베디드 이미지를 본문과 떨어진
     * 파일 태그 영역에 두고 그림 컨트롤과 이어 줄 식별자를 남기지 않는다. 게다가
     * {@code ImageSieve}가 정보 없는 이미지를 걸러 내므로 등장 순서로 짝지으면 색인이
     * 어긋난다 — <b>엉뚱한 사진에 캡션이 붙는 것은 캡션이 없는 것보다 나쁘다.</b>
     * 표 캡션은 컨트롤에 직접 달려 있어 {@link Table#caption()}에 담는다.
     */
    public record Text(String text) implements Item {
    }

    /**
     * 표 하나. 행·열 수와 셀 목록을 담으며, 셀 좌표는 이미 격자 색인으로 환산돼 있다.
     *
     * @param caption 표에 달린 캡션(없으면 null)
     */
    public record Table(int rows, int cols, List<Cell> cells, String caption) implements Item {
    }

    /**
     * 표의 셀 하나 — 격자 색인과 병합 span, 그리고 셀 안 문단을 이어 붙인 텍스트.
     *
     * <p>셀 안에 중첩 표가 있으면 그 셀 텍스트까지 평평하게 이어 붙인다. hwplib 경로
     * ({@code HwplibExtractor})가 {@code getNormalString()}으로 하는 것과 같은 처리라
     * 매퍼가 형식별로 다른 모양을 보지 않는다.
     */
    public record Cell(int row, int col, int rowSpan, int colSpan, String text) {
    }

    /** 임베디드 이미지 하나 — 파일 태그 영역에서 읽은 원본 바이트와 형식 힌트. */
    public record Image(String name, String formatHint, byte[] data) {
    }

    /**
     * 본문 글자 수 — 스캔 판별({@link Hwp3ScanDetector})의 근거다.
     *
     * <p>표 셀·캡션까지 센다. 머리말·꼬리말·각주는 애초에 {@code items}에 없으므로
     * 따로 걸러 낼 필요가 없다.
     */
    public int bodyChars() {
        int total = 0;
        for (Item item : items) {
            if (item instanceof Text text) {
                total += text.text().trim().length();
            } else if (item instanceof Table table) {
                for (Cell cell : table.cells()) {
                    total += cell.text().trim().length();
                }
                // 캡션이 문단 항목에서 빠졌으므로 여기서 세지 않으면 본문 분량이 줄어
                // 캡션만 있는 문서가 스캔본으로 뒤집힌다
                if (table.caption() != null) {
                    total += table.caption().trim().length();
                }
            }
        }
        return total;
    }
}
