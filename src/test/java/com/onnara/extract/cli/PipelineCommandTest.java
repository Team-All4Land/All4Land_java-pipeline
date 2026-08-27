package com.onnara.extract.cli;

import com.onnara.extract.common.AgencyRegistry;
import com.onnara.extract.common.LoadStep;
import com.onnara.extract.common.model.RawDocument;
import com.onnara.extract.db.DbLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link PipelineCommand#toFailedAttachments} 단위 테스트 — {@code --failures} 산출물과
 * 같은 모양의 실패 행이 적재용 레코드로 옮겨지는 자리다.
 *
 * <p>실패도 첨부 행으로 남게 된 뒤로, 여기서 값이 어긋나면 <b>실패 통계 자체가 조용히
 * 틀어진다</b>. 특히 기관이 빠지면 그 기관의 실패가 "기관 미상"으로 새어 기관별 수집률이
 * 실제보다 좋아 보인다.
 */
class PipelineCommandTest {

    /** 게시 중인 게시판 하나를 만들고 그 폴더를 스캔한 레지스트리를 돌려준다. */
    private static AgencyRegistry registryWithBoard(Path root, String folder) throws IOException {
        Files.createDirectories(root.resolve(folder));
        return AgencyRegistry.scan(root);
    }

    /**
     * 실패 행을 {@link PipelineSupport#reportFailure}로 만든다 — 손으로 맵을 짜면
     * 산출물의 키와 여기서 읽는 키가 어긋나도 테스트가 통과해 버린다.
     */
    private static Map<String, Object> failureRow(Path file) {
        return PipelineSupport.reportFailure(
                file, LoadStep.EXTRACT, "IOException: 중앙 디렉터리를 찾을 수 없음", "ZIP_CORRUPT");
    }

    /** 실패 한 건을 옮긴 결과. */
    private static DbLoader.FailedAttachment only(
            AgencyRegistry registry, Map<String, Object> failure) {
        List<DbLoader.FailedAttachment> rows =
                PipelineCommand.toFailedAttachments(registry, List.of(failure));
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    /**
     * 산출물이 실제로 쓰는 키를 그대로 읽어야 한다 — 단계·갈래·사유가 각 컬럼으로 간다.
     *
     * <p>{@code reportFailure}가 키를 바꾸면 여기서 깨진다. 그게 이 테스트의 목적이다.
     */
    @Test
    void readsTheKeysThatTheFailuresReportActuallyWrites(@TempDir Path root) throws IOException {
        AgencyRegistry registry = registryWithBoard(root, "12_1_목포시청");
        Path file = root.resolve("12_1_목포시청").resolve("1_1_공유수면 점용허가 고시.hwp");

        DbLoader.FailedAttachment row = only(registry, failureRow(file));

        assertEquals("1_1_공유수면 점용허가 고시.hwp", row.fileName(), "파일명은 경로가 아니다");
        assertEquals("EXTC", row.stage(), "FAIL_STEP_CD로 가는 표준코드");
        assertEquals("ZIP_CORRUPT", row.kind());
        assertEquals("IOException: 중앙 디렉터리를 찾을 수 없음", row.message());
    }

    /**
     * 실패해도 게시물 행은 만들어지므로 기관이 붙어야 한다.
     *
     * <p>안 붙이면 그 기관의 실패 건이 "기관 미상"으로 새어, 기관별 수집률이 실제보다
     * 좋아 보인다 — 추출을 한 건도 못 한 기관이 화면에서 사라진다.
     */
    @Test
    void attachesTheBoardTheFileCameFrom(@TempDir Path root) throws IOException {
        AgencyRegistry registry = registryWithBoard(root, "12_1_목포시청");
        Path file = root.resolve("12_1_목포시청").resolve("1_1_고시문.hwp");

        AgencyRegistry.SourceBoard board = only(registry, failureRow(file)).board();

        assertNotNull(board, "폴더가 규약에 맞으면 수집처를 알 수 있다");
        assertEquals(12, board.agncyNo());
        assertEquals("목포시청", board.agncyNm());
        assertEquals("POST", board.boardCd());
    }

    /**
     * 기관을 모르는 파일도 행은 만들어야 한다 — 기관이 없다고 실패 자체를 버리면
     * 그 파일은 수집조차 안 된 것과 구별되지 않는다.
     */
    @Test
    void stillMakesARowWhenTheBoardIsUnknown(@TempDir Path root) throws IOException {
        AgencyRegistry registry = registryWithBoard(root, "12_1_목포시청");
        // 루트 바로 밑 — 사이에 폴더가 없으니 어느 게시판에서 왔는지 알 수 없다
        Path file = root.resolve("정체불명.hwp");

        DbLoader.FailedAttachment row = only(registry, failureRow(file));

        assertNull(row.board());
        assertEquals("정체불명.hwp", row.fileName());
    }

    /**
     * 없는 값은 {@code "null"} 문자열이 아니라 {@code null}로 가야 한다 — 컬럼을 비우는 것과
     * 네 글자를 적어 넣는 것은 전혀 다르다. 후자는 집계에서 갈래 하나로 잡힌다.
     */
    @Test
    void keepsAbsentFieldsNullInsteadOfTheStringNull(@TempDir Path root) throws IOException {
        AgencyRegistry registry = registryWithBoard(root, "12_1_목포시청");
        Map<String, Object> bare = new LinkedHashMap<>();
        bare.put("file", root.resolve("12_1_목포시청").resolve("1_1_고시문.hwp").toString());

        DbLoader.FailedAttachment row = only(registry, bare);

        assertNull(row.stage());
        assertNull(row.kind());
        assertNull(row.message());
    }

    /**
     * 실패 행의 경로가 성공 행의 경로와 <b>같은 모양</b>이어야 한다.
     *
     * <p>{@code ATCH_FILE_PATH} 한 컬럼에 두 경로가 섞여 들어가는데 정규화는 각자 하고 있다.
     * 한쪽만 바뀌면 같은 파일이 성공했을 때와 실패했을 때 다른 문자열로 남는다.
     */
    @Test
    void normalisesThePathTheSameWayTheSuccessPathDoes(@TempDir Path root) throws IOException {
        AgencyRegistry registry = registryWithBoard(root, "12_1_목포시청");
        Path file = root.resolve("12_1_목포시청").resolve("1_1_고시문.hwp");

        String onSuccess = PipelineSupport.tagFormats(new RawDocument(), file, "hwp")
                .getAtchFilePath();

        assertEquals(onSuccess, only(registry, failureRow(file)).filePath());
    }
}
