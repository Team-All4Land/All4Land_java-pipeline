package com.onnara.extract.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onnara.extract.common.model.RawCell;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.RawParagraph;
import com.onnara.extract.common.model.RawTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 본문 분량 측정({@link DocumentSize}) 단위 테스트 — 캡션이 계약에 들어오며 생긴 축을 지킨다.
 */
class DocumentSizeTest {

    /**
     * 캡션은 본문으로 센다.
     *
     * <p>캡션은 이제 {@code content} 문단이 아니라 표·그림의 전용 필드에 담긴다. 여기서
     * 세지 않으면 같은 문서의 {@code body_chars}가 예전보다 줄어들고, 그만큼
     * {@link LoadPolicy}의 적재 판정이 소리 없이 달라진다.
     */
    @Test
    void countsCaptionsAsBody() {
        RawDocument raw = new RawDocument("고시문.hwp", "hwp", false);
        RawTable table = new RawTable(1, 1, List.of(new RawCell(0, 0, 1, 1, "값")),
                List.of(List.of("값")));
        table.setCaption("표1");
        raw.getContent().add(table);

        RawImage image = new RawImage("고시문_img0.png", 100);
        image.setCaption("그림2");
        raw.getImages().add(image);

        // 셀 "값"(1) + 표 캡션 "표1"(2) + 그림 캡션 "그림2"(3)
        assertEquals(6, DocumentSize.bodyChars(raw));
    }

    /** 캡션이 없으면 예전과 똑같이 센다 — 이 필드가 기존 판정을 흔들지 않는다. */
    @Test
    void isUnchangedWithoutCaptions() {
        RawDocument raw = new RawDocument("고시문.hwp", "hwp", false);
        raw.getContent().add(new RawParagraph("본문 열 글자"));
        raw.getContent().add(new RawTable(1, 1, List.of(new RawCell(0, 0, 1, 1, "값")),
                List.of(List.of("값"))));
        raw.getImages().add(new RawImage("고시문_img0.png", 100));

        assertEquals("본문열글자".length() + 1, DocumentSize.bodyChars(raw));
    }

    /**
     * 캡션이 없는 문서의 raw JSON에는 {@code caption} 키 자체가 없어야 한다.
     *
     * <p>계약(§4)은 Python 산출물과 <b>바이트 수준 호환</b>을 요구한다. 필드를 추가하면서
     * {@code "caption": null}이 섞여 나오면 그 요구가 깨진다.
     */
    @Test
    void omitsCaptionKeyWhenAbsent() throws Exception {
        RawDocument raw = new RawDocument("고시문.hwp", "hwp", false);
        raw.getContent().add(new RawTable(1, 1, List.of(new RawCell(0, 0, 1, 1, "값")),
                List.of(List.of("값"))));
        raw.getImages().add(new RawImage("고시문_img0.png", 100));

        String json = new ObjectMapper().writeValueAsString(raw);

        assertFalse(json.contains("caption"), json);
    }

    /** 캡션이 있으면 그대로 실린다. */
    @Test
    void writesCaptionWhenPresent() throws Exception {
        RawDocument raw = new RawDocument("고시문.hwp", "hwp", false);
        RawTable table = new RawTable(1, 1, List.of(new RawCell(0, 0, 1, 1, "값")),
                List.of(List.of("값")));
        table.setCaption("<표 1> 허가 내역");
        raw.getContent().add(table);

        String json = new ObjectMapper().writeValueAsString(raw);

        assertTrue(json.contains("\"caption\":\"<표 1> 허가 내역\""), json);
    }

    /** 빈 캡션은 없는 것으로 눕힌다 — 빈 문자열이 JSON에 실리면 "캡션이 있다"로 읽힌다. */
    @Test
    void treatsBlankCaptionAsAbsent() {
        RawTable table = new RawTable(1, 1, List.of(), List.of());
        table.setCaption("   ");

        assertEquals(null, table.getCaption());
    }
}
