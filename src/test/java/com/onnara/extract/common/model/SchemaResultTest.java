package com.onnara.extract.common.model;

import com.onnara.extract.common.AgencyRegistry;
import com.onnara.extract.common.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SchemaResult} 스키마 JSON 왕복 단위 테스트. */
class SchemaResultTest {

    /**
     * 수집처가 스키마 JSON을 왕복해야 한다 — {@code pipeline}과 {@code map} → {@code load}가
     * 같은 기관을 봐야 하기 때문이다.
     *
     * <p>폴더는 {@code pipeline} 경로에서만 보인다. 여기서 새면 {@code load}로 재적재할 때
     * 기관이 조용히 NULL로 되돌아가고, 그 손실은 집계를 뽑고 나서야 드러난다.
     */
    @Test
    void carriesTheSourceBoardThroughJson() throws Exception {
        SchemaResult schema = new SchemaResult("6034_1_고시문.hml", "hml", false, "hml-dom");
        schema.setNotiSn(6034);
        schema.setAtchSn(1);
        schema.setSourceBoard(
                new AgencyRegistry.SourceBoard(12, "목포시청", "LOCL", "CLSD"));

        String json = Json.MAPPER.writeValueAsString(schema);
        assertTrue(json.contains("\"source_board\""), json);
        assertTrue(json.contains("\"agncy_no\":12"), "레코드 컴포넌트는 snake_case로 나가야 한다");

        SchemaResult reloaded = Json.MAPPER.readValue(json, SchemaResult.class);
        assertEquals(schema.getSourceBoard(), reloaded.getSourceBoard());
    }

    /** 수집처를 모르는 파일은 키 자체를 내보내지 않는다 — 옛 스키마 JSON과 바이트가 같아진다. */
    @Test
    void omitsTheSourceBoardWhenUnknown() throws Exception {
        SchemaResult schema = new SchemaResult("고시문.hml", "hml", false, "hml-dom");

        String json = Json.MAPPER.writeValueAsString(schema);
        assertTrue(!json.contains("source_board"), json);
        assertNull(Json.MAPPER.readValue(json, SchemaResult.class).getSourceBoard());
    }
}
