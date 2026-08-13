package com.onnara.extract.docs;

import com.onnara.extract.common.Json;
import com.onnara.extract.common.Synonyms;
import com.onnara.extract.common.model.NoticeRecord;
import com.onnara.extract.common.model.SchemaResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 검토용 문서 생성({@link SynonymsDoc}, {@link ExtrasReport})의 단위 테스트. */
class DocsTest {

    /** 라벨 하나만 extras에 담은 최소 스키마 결과를 파일로 저장한다. */
    private static void writeSchema(Path dir, String name, Map<String, String> extras,
                                    String location) throws IOException {
        SchemaResult schema = new SchemaResult(name, "hml", false, "hml-dom");
        NoticeRecord.Builder record = new NoticeRecord.Builder()
                .set("agency", "군산지방해양수산청")
                .set("location", location);
        if (extras != null) {
            extras.forEach(record::extra);
        }
        schema.getRecords().add(record.build());
        Json.PRETTY.writeValue(dir.resolve(name + ".schema.json").toFile(), schema);
    }

    /** 사전 문서가 모든 표준 필드와 등록된 라벨을 빠짐없이 싣는지 검증한다. */
    @Test
    void synonymsDocListsEveryFieldAndSynonym() {
        String md = SynonymsDoc.render();

        for (Synonyms.FieldSpec field : Synonyms.fields()) {
            assertTrue(md.contains("`" + field.canonical() + "`"),
                    "문서에 표준 필드가 빠짐: " + field.canonical());
            assertTrue(md.contains(field.display()), "문서에 표시명이 빠짐: " + field.display());
            for (String synonym : field.rawSynonyms()) {
                assertTrue(md.contains(synonym),
                        "문서에 동의어가 빠짐: " + field.canonical() + " / " + synonym);
            }
        }
        assertTrue(md.contains(Synonyms.version()), "사전 버전이 표기돼야 함");
        assertTrue(md.contains("## 사전에 라벨 추가하기"), "보강 절차 안내가 있어야 함");
    }

    /**
     * 검토 리포트가 여러 문서의 미매핑 라벨을 합산해 빈도순으로 내고,
     * 표준 필드 채움률을 함께 집계하는지 검증한다.
     */
    @Test
    void extrasReportAggregatesUnmappedLabelsByFrequency(@TempDir Path dir) throws IOException {
        writeSchema(dir, "고시_A.hml", Map.of("공작물의종류", "선가대(6기)"), "군산시 비응항");
        writeSchema(dir, "고시_B.hml", Map.of("공작물의종류", "선가대(2기)", "사업비", "198백만원"), null);

        String md = ExtrasReport.render(List.of(
                dir.resolve("고시_A.hml.schema.json"), dir.resolve("고시_B.hml.schema.json")));

        assertTrue(md.contains("공작물의종류"), "미매핑 라벨이 리포트에 있어야 함");
        assertTrue(md.contains("사업비"));
        // 2개 문서에 걸쳐 2건인 라벨이, 1건짜리보다 위에 와야 한다
        assertTrue(md.indexOf("공작물의종류") < md.indexOf("사업비"), "빈도순 정렬이 아님");

        // 채움률: location은 2건 중 1건만 채워졌다
        assertTrue(md.contains("## 표준 필드 채움률"));
        assertTrue(md.contains("| `location` | 점용·사용 장소 | 1 / 2 | 50% |"),
                "채움률 집계가 어긋남:\n" + md);
    }

    /** extras가 전혀 없으면 "미매핑 없음"으로 명시하는지 검증한다. */
    @Test
    void extrasReportStatesWhenNothingIsUnmapped(@TempDir Path dir) throws IOException {
        writeSchema(dir, "고시_C.hml", null, "군산시 비응항");

        String md = ExtrasReport.render(List.of(dir.resolve("고시_C.hml.schema.json")));

        assertTrue(md.contains("미매핑 라벨이 없습니다"), "미매핑 0건임을 밝혀야 함");
        assertFalse(md.contains("| # | 라벨 |"), "빈 표를 내면 안 됨");
    }
}
