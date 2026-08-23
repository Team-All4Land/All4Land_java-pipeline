package com.onnara.extract.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link AgencyRegistry} 폴더 집합 → 기관번호 확정 단위 테스트. */
class AgencyRegistryTest {

    /** 폴더들을 만들고 그 안에 첨부 하나씩 넣는다 — 반환값은 만든 첨부 경로들. */
    private static void folders(Path root, String... names) throws IOException {
        for (String name : names) {
            Files.createDirectories(root.resolve(name));
        }
    }

    /**
     * 이름이 같은 하위번호 폴더는 게시판만 둘인 한 기관이다.
     *
     * <p>기관을 쪼개면 "목포시청 수집 현황"이 두 줄로 나뉘어 합계를 손으로 더해야 한다.
     */
    @Test
    void groupsBoardsOfTheSameAgencyUnderOneNumber(@TempDir Path root) throws IOException {
        folders(root, "12_1_목포시청", "12_2_목포시청_지난자료");
        AgencyRegistry registry = AgencyRegistry.scan(root);

        assertEquals(List.of(new AgencyRegistry.Agency(12, "목포시청", "LOCL")),
                registry.agencies());

        AgencyRegistry.SourceBoard open =
                registry.boardOf(root.resolve("12_1_목포시청").resolve("1_1_고시.hwp")).orElseThrow();
        AgencyRegistry.SourceBoard closed = registry.boardOf(
                root.resolve("12_2_목포시청_지난자료").resolve("2_1_고시.hwp")).orElseThrow();

        assertEquals(12, open.agncyNo());
        assertEquals(12, closed.agncyNo());
        assertEquals("POST", open.boardCd());
        assertEquals("CLSD", closed.boardCd());
    }

    /**
     * 한 번호 아래 기관이 갈리면 그 번호는 아무도 쓰지 않고 최대번호 뒤로 새로 발급한다.
     *
     * <p>전자관보 폴더가 그 경우다 — 72_1은 농림축산식품부, 72_2는 새만금개발청이라
     * 72 하나에 밀어 넣을 수 없다. 71이 최대번호이던 판에서 72_1·72_2가 들어오면 73·74가 된다.
     */
    @Test
    void issuesFreshNumbersWhenOneFolderNumberHoldsTwoAgencies(@TempDir Path root)
            throws IOException {
        folders(root, "70_새만금개발청", "71_농림축산식품부",
                "72_1_전자관보_농림축산식품부", "72_2_전자관보_새만금개발청");
        AgencyRegistry registry = AgencyRegistry.scan(root);

        assertEquals(List.of(
                        new AgencyRegistry.Agency(70, "새만금개발청", "CNTL"),
                        new AgencyRegistry.Agency(71, "농림축산식품부", "CNTL"),
                        new AgencyRegistry.Agency(73, "농림축산식품부", "GZT"),
                        new AgencyRegistry.Agency(74, "새만금개발청", "GZT")),
                registry.agencies());

        // 충돌한 번호 72는 비워 둔다 — 둘 중 하나에 주면 나머지 하나만 새 번호를 받아
        // "어느 쪽이 원래 72였나"가 폴더 정렬 순서에 좌우된다
        assertTrue(registry.agencies().stream().noneMatch(a -> a.agncyNo() == 72));
    }

    /** 같은 폴더 집합이면 매 실행 같은 번호가 나와야 한다 — 재적재가 멱등하려면 필요하다. */
    @Test
    void assignsTheSameNumbersOnEveryScan(@TempDir Path root) throws IOException {
        folders(root, "12_1_목포시청", "12_2_목포시청_지난자료",
                "72_1_전자관보_농림축산식품부", "72_2_전자관보_새만금개발청");

        assertEquals(AgencyRegistry.scan(root).agencies(), AgencyRegistry.scan(root).agencies());
    }

    /**
     * 첨부가 한 건도 없는 폴더도 기관으로 남긴다.
     *
     * <p>"긁긴 했는데 아무것도 못 건진 기관"이 DB에서 안 보이면, 수집 누락과 애초에 자료가
     * 없던 기관을 구분할 수 없다. 실패·적재제외를 행으로 남기는 것과 같은 이유다.
     */
    @Test
    void registersAgenciesWithNoAttachments(@TempDir Path root) throws IOException {
        folders(root, "48_포항시청");

        assertEquals(List.of(new AgencyRegistry.Agency(48, "포항시청", "LOCL")),
                AgencyRegistry.scan(root).agencies());
    }

    /**
     * 규약 밖 폴더와 루트 직속 파일은 기관을 못 받는다 — 첨부는 적재되고 기관만 NULL이 된다.
     */
    @Test
    void leavesFilesOutsideTheConventionWithoutAnAgency(@TempDir Path root) throws IOException {
        folders(root, "samples");
        Files.createFile(root.resolve("6034_1_고시.hwp"));
        AgencyRegistry registry = AgencyRegistry.scan(root);

        assertEquals(List.of(), registry.agencies());
        assertTrue(registry.boardOf(root.resolve("6034_1_고시.hwp")).isEmpty());
        assertTrue(registry.boardOf(root.resolve("samples").resolve("고시.hwp")).isEmpty());
    }

    /** 기관 폴더 안의 하위 폴더는 그 기관 것이다 — 첫 경로 조각만 본다. */
    @Test
    void resolvesNestedFilesToTheTopLevelFolder(@TempDir Path root) throws IOException {
        folders(root, "1_인천지방해양수산청");
        Path nested = root.resolve("1_인천지방해양수산청").resolve("2024").resolve("83_1.hml");

        assertEquals(1, AgencyRegistry.scan(root).boardOf(nested).orElseThrow().agncyNo());
    }

    /** 입력 폴더가 없으면 빈 레지스트리 — 배치가 여기서 죽으면 안 된다. */
    @Test
    void toleratesMissingInputRoot(@TempDir Path root) throws IOException {
        AgencyRegistry registry = AgencyRegistry.scan(root.resolve("없는폴더"));

        assertEquals(List.of(), registry.agencies());
        assertTrue(registry.boardOf(root.resolve("x.hwp")).isEmpty());
    }

    /** 이름이 같아도 수집 경로가 다르면 다른 기관이다 — gazette와 central은 합쳐지지 않는다. */
    @Test
    void separatesTheSameNameCollectedThroughDifferentRoutes(@TempDir Path root)
            throws IOException {
        folders(root, "71_농림축산식품부", "72_1_전자관보_농림축산식품부");
        List<AgencyRegistry.Agency> agencies = AgencyRegistry.scan(root).agencies();

        assertEquals(2, agencies.size());
        assertNotEquals(agencies.get(0).kndCd(), agencies.get(1).kndCd());
    }
}
