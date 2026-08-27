package com.onnara.extract.db;

import com.onnara.extract.common.AgencyRegistry;
import com.onnara.extract.common.DbStandard;
import com.onnara.extract.common.AttributeRows;
import com.onnara.extract.common.Errors;
import com.onnara.extract.common.LoadStep;
import com.onnara.extract.common.SourceFileName;
import com.onnara.extract.common.NoticeItems;
import com.onnara.extract.common.model.NoticeRecord;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.SchemaResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 적재 — 공고 → 첨부 → 항목값 3계층으로 저장한다.
 *
 * <p>멱등 단위는 <b>첨부파일</b>이다({@code (NOTI_SN, ATCH_SN)}). 게시물 단위로 지우면
 * 파일을 한 건씩 처리하는 도중 같은 게시물의 앞선 첨부가 함께 날아간다. 첨부 행을 지우면
 * CASCADE로 이미지와 항목값이 함께 정리되므로 재적재가 안전하다.
 *
 * <p>성공한 파일만 넣지 않는다 — 실패·항목값 적재제외도 {@code PROC_STTS_CD}를 달아 행으로 남긴다.
 * 성공만 적재하면 "첨부 401건 중 추출 0건"인 기관이 DB에서 아예 보이지 않는다.
 *
 * <p>기관은 첨부보다 <b>먼저</b> 한 번에 넣는다 — {@code OS_NOTI_BAS.INSTT_SN}가 FK라
 * 순서가 뒤바뀌면 첫 적재가 FK 위반으로 죽는다({@link ReferenceSync}를 앞세우는 것과 같은 이유).
 */
public final class DbLoader implements AutoCloseable {

    /** 기관 행 — 입력 폴더명에서 확정한 목록을 배치 시작에 한 번 반영한다. */
    private static final String UPSERT_AGENCY = """
            INSERT INTO OS_INSTT_BAS (INSTT_SN, INSTT_NM, INSTT_KND_CD) VALUES (?, ?, ?)
            ON CONFLICT (INSTT_SN) DO UPDATE SET
                INSTT_NM = EXCLUDED.INSTT_NM, INSTT_KND_CD = EXCLUDED.INSTT_KND_CD
            """;

    /**
     * 게시물 행 — 같은 게시물의 첨부가 여럿이므로 값은 COALESCE로 합친다.
     *
     * <p>{@code DO NOTHING}이 아닌 이유: 같은 게시물의 두 번째 첨부가 들어올 때 첫 첨부가 채운
     * 기관을 덮어 지우면 안 되고, 폴더를 모르는 경로({@code map} → {@code load})로 재적재해도
     * 이미 채운 값이 날아가면 안 된다. 새 정보가 있으면 이기고, 없으면 기존을 지킨다.
     */
    private static final String INSERT_NOTICE = """
            INSERT INTO OS_NOTI_BAS (NOTI_SN, INSTT_SN, BBS_STTS_CD) VALUES (?, ?, ?)
            ON CONFLICT (NOTI_SN) DO UPDATE SET
                INSTT_SN = COALESCE(EXCLUDED.INSTT_SN, OS_NOTI_BAS.INSTT_SN),
                BBS_STTS_CD = COALESCE(EXCLUDED.BBS_STTS_CD, OS_NOTI_BAS.BBS_STTS_CD)
            """;

    /** 멱등 재적재를 위한 기존 첨부 삭제(이미지·항목값은 CASCADE로 함께 정리). */
    private static final String DELETE_ATTACHMENT =
            "DELETE FROM OS_ATCH_FILE_DTL WHERE NOTI_SN = ? AND ATCH_SN = ?";

    /**
     * 첨부 1행 — 파일 메타 + 문서 단위 메타 + 추출 상태.
     *
     * <p>처분 건수를 컬럼으로 들고 있지 않다 — {@code COUNT(DISTINCT DSPS_SN)}으로 나오는
     * 값을 적재 로직과 따로 동기화하면 언젠가 둘이 어긋난다.
     */
    private static final String INSERT_ATTACHMENT = """
            INSERT INTO OS_ATCH_FILE_DTL (
                NOTI_SN, ATCH_SN, ATCH_FILE_NM, ATCH_FILE_PATH, PROC_STTS_CD,
                FAIL_STEP_CD, FAIL_KND_CD, FAIL_MSG_CTNT, EXCL_RSN_CTNT,
                FILE_EXTN_NM, ACTL_FILE_EXTN_NM, SCAN_YN, EXTC_ENGN_NM,
                NOTI_KND_CD, NOTI_NO, NOTI_DT, NOTI_TTL
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** 이미지 1행(첨부당 N건, 배치 실행). */
    private static final String INSERT_IMAGE = """
            INSERT INTO OS_ATCH_IMG_DTL (NOTI_SN, ATCH_SN, IMG_SN, IMG_CPTN_CTNT, IMG_FILE_PATH)
            VALUES (?, ?, ?, ?, ?)
            """;

    /** 항목값 1행(레코드당 N건, 배치 실행). */
    private static final String INSERT_ATTRIBUTE = """
            INSERT INTO OS_NOTI_ITEM_VAL_DTL (NOTI_SN, ATCH_SN, DSPS_SN, NOTI_ITEM_CD, RPT_SN, ITEM_VAL_CTNT)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    /** 라벨값 1행(레코드당 N건, 배치 실행) — 표준항목으로 매핑되지 못한 값. */
    private static final String INSERT_LABEL = """
            INSERT INTO OS_NOTI_LBL_VAL_DTL (NOTI_SN, ATCH_SN, DSPS_SN, ITEM_LBL_NM, ITEM_VAL_CTNT)
            VALUES (?, ?, ?, ?, ?)
            """;

    /** 처리상태코드(CD_PROC_STTS) — 추출 성공. */
    private static final String STATUS_OK = "OK";

    /** 처리상태코드(CD_PROC_STTS) — 판별·추출·매핑 실패. */
    private static final String STATUS_FAILED = "FAIL";

    /**
     * 처리상태코드(CD_PROC_STTS) — 안내문류로 보고 <b>항목값 적재만</b> 건너뜀.
     *
     * <p>파일을 통째로 빼는 것이 아니다. 첨부 행도 이미지도 그대로 들어가고
     * {@code OS_NOTI_ITEM_VAL_DTL}·{@code OS_NOTI_LBL_VAL_DTL}만 빠진다.
     */
    private static final String STATUS_SKIPPED = "SKIP";

    /** 배치 전체에서 공유하는 커넥션(수동 커밋). */
    private final Connection conn;

    /** 커넥션을 확보하고 자동 커밋을 꺼 파일 단위 세이브포인트 격리를 준비한다. */
    public DbLoader(DataSource dataSource) throws SQLException {
        this.conn = dataSource.getConnection();
        this.conn.setAutoCommit(false);
    }

    /**
     * 판별·추출 단계에서 실패한 첨부 1건 — 성공 산출물이 없어 {@link SchemaResult}로 표현할 수 없다.
     *
     * @param fileName 첨부파일명
     * @param filePath 첨부파일의 정규화된 절대경로
     * @param stage    실패한 단계의 표준코드(CD_FAIL_STEP: DTCT / EXTC / SAVE / LOAD)
     * @param kind     실패 갈래({@link com.onnara.extract.detect.FailureKind})
     * @param message  원인 체인까지 담은 사유 문장
     * @param board    수집처 — 실패해도 게시물 행은 만들어지므로 기관이 붙어야 한다. 모르면 null
     */
    public record FailedAttachment(String fileName, String filePath, String stage, String kind, String message,
                                   AgencyRegistry.SourceBoard board) {
    }

    /**
     * 성공·실패 첨부를 모두 적재한다. 첨부 단위 실패는 로그 후 다음 건으로 계속 진행한다.
     *
     * <p>매핑 단계에서 적재 제외 판정을 받은 파일({@code db_skip_reason})도 첨부 행은 남긴다 —
     * 판정 사실이 DB에 있어야 "왜 이 파일의 값이 없는지"를 나중에 설명할 수 있다.
     */
    public LoadStats loadAll(List<SchemaResult> files, List<FailedAttachment> failures)
            throws SQLException {
        int agencies = upsertAgencies(collectAgencies(files, failures));
        int filesOk = 0;
        int filesFailed = 0;
        int filesSkipped = 0;
        int recordsInserted = 0;
        int labelsInserted = 0;
        int imagesInserted = 0;

        for (SchemaResult file : files) {
            Savepoint savepoint = conn.setSavepoint();
            try {
                Counts counts = loadOne(file);
                conn.releaseSavepoint(savepoint);
                if (file.isExcluded()) {
                    filesSkipped++;
                    System.out.println("[항목값 적재제외] " + file.getAtchFileNm() + ": "
                            + file.getExclRsnCtnt()
                            + " — 안내문류로 보고 항목값 적재만 건너뜁니다(첨부·이미지는 적재)");
                } else {
                    filesOk++;
                }
                recordsInserted += counts.records();
                labelsInserted += counts.labels();
                imagesInserted += counts.images();
            } catch (Exception e) {
                conn.rollback(savepoint);
                filesFailed++;
                System.out.println("[실패] " + file.getAtchFileNm() + ": " + Errors.describe(e));
                recordLoadFailure(file, e);
            }
        }

        for (FailedAttachment failure : failures) {
            Savepoint savepoint = conn.setSavepoint();
            try {
                loadFailure(failure);
                conn.releaseSavepoint(savepoint);
            } catch (Exception e) {
                conn.rollback(savepoint);
                System.out.println("[실패기록 실패] " + failure.fileName() + ": " + Errors.describe(e));
            }
        }

        conn.commit();
        return new LoadStats(agencies, filesOk, filesFailed, filesSkipped,
                recordsInserted, labelsInserted, imagesInserted);
    }

    /**
     * 이번 배치가 본 기관을 중복 없이 모은다 — 기관번호 오름차순.
     *
     * <p>스키마 JSON과 실패 목록 양쪽에서 긁는다. 첨부를 한 건도 못 건진 기관을 따로 넣고 싶으면
     * {@link #loadAgencies}로 목록을 통째로 넘긴다.
     */
    private static Collection<AgencyRegistry.Agency> collectAgencies(
            List<SchemaResult> files, List<FailedAttachment> failures) {
        Map<Integer, AgencyRegistry.Agency> byNo = new LinkedHashMap<>();
        for (SchemaResult file : files) {
            addAgency(byNo, file.getSourceBoard());
        }
        for (FailedAttachment failure : failures) {
            addAgency(byNo, failure.board());
        }
        List<AgencyRegistry.Agency> sorted = new ArrayList<>(byNo.values());
        sorted.sort(Comparator.comparingInt(AgencyRegistry.Agency::agncyNo));
        return sorted;
    }

    /** 수집처에서 기관을 뽑아 담는다. 같은 번호가 또 오면 처음 것을 지킨다. */
    private static void addAgency(Map<Integer, AgencyRegistry.Agency> byNo,
                                  AgencyRegistry.SourceBoard board) {
        if (board != null) {
            byNo.putIfAbsent(board.agncyNo(), new AgencyRegistry.Agency(
                    board.agncyNo(), board.agncyNm(), board.kndCd()));
        }
    }

    /**
     * 기관 목록을 통째로 반영하고 커밋한다 — 첨부 적재보다 <b>먼저</b> 불러야 한다.
     *
     * <p>{@link #loadAll}은 파일에 딸린 기관만 넣는다. 첨부가 0건인 폴더까지 남기려면
     * 배치가 {@link AgencyRegistry#agencies()}를 이 메서드로 먼저 넘겨야 한다 —
     * "긁긴 했는데 아무것도 못 건진 기관"이 DB에서 보이지 않으면 수집 누락과
     * 애초에 자료가 없던 기관을 구분할 수 없다.
     *
     * @return 반영한 기관 수
     */
    public int loadAgencies(Collection<AgencyRegistry.Agency> agencies) throws SQLException {
        int count = upsertAgencies(agencies);
        conn.commit();
        return count;
    }

    /** 기관 upsert 배치 실행. 커밋은 호출부가 한다. */
    private int upsertAgencies(Collection<AgencyRegistry.Agency> agencies) throws SQLException {
        if (agencies.isEmpty()) {
            return 0;
        }
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_AGENCY)) {
            for (AgencyRegistry.Agency agency : agencies) {
                ps.setInt(1, agency.agncyNo());
                ps.setString(2, agency.agncyNm());
                ps.setString(3, agency.kndCd());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return agencies.size();
    }

    /**
     * 첨부 1건을 멱등 적재한다(기존 행 삭제 후 재삽입).
     * 트랜잭션 커밋/롤백은 호출부({@link #loadAll})가 책임진다.
     *
     * @return 삽입한 (항목값 행 수, 라벨값 행 수, 이미지 행 수)
     */
    public Counts loadOne(SchemaResult file) throws SQLException {
        SourceFileName.Parsed name = file.attachmentKey();
        prepareSlot(name, file.getSourceBoard());

        List<NoticeRecord> records = file.getRecords();
        // 적재제외 파일은 첨부 메타와 이미지까지 남기고 항목값·라벨값만 넣지 않는다
        boolean loadValues = !file.isExcluded();
        NoticeRecord meta = records.isEmpty() ? null : records.get(0);

        insertAttachment(file, name, meta);

        int attributes = 0;
        int labels = 0;
        if (loadValues) {
            // 레코드마다 한 번만 가른다 — AttributeRows.of는 필드마다 사전을 훑으므로
            // 항목값과 라벨값을 넣으며 따로 부르면 같은 일을 두 번 한다.
            List<AttributeRows.Split> split = new ArrayList<>(records.size());
            records.forEach(record -> split.add(AttributeRows.of(record)));
            attributes = insertAttributes(name, split);
            labels = insertLabels(name, split);
        }
        int images = insertImages(file, name);
        return new Counts(attributes, labels, images);
    }

    /**
     * 적재 자체가 실패한 첨부를 {@code FAIL_STEP_CD='LOAD'} 행으로 남긴다.
     *
     * <p>롤백된 뒤라 자리는 비어 있다. 여기에 항목값을 뺀 최소 행을 다시 넣으면, 값 하나 때문에
     * 통째로 사라지던 첨부가 "적재에서 넘어졌다"는 사실만은 DB에 남긴다 — 그러지 않으면 콘솔을
     * 놓친 순간 그 첨부는 수집조차 안 된 것과 구별되지 않는다.
     *
     * <p><b>모든 적재 실패를 건지지는 못한다.</b> 연결이 끊겼거나 DB가 죽어서 실패한 것이라면
     * 이 재삽입도 같은 이유로 죽는다. 다만 그런 실패는 전건이 함께 죽어 콘솔에 곧바로 드러나므로,
     * 이 경로가 실제로 건지는 것은 놓치기 쉬운 <b>행 단위 원인</b>(제약 위반, 값 길이 초과 등)이다.
     *
     * <p>실패종류코드는 비운다 — {@link com.onnara.extract.detect.FailureKind}는 문서 판별·추출의
     * 갈래이고 적재 실패에 들어맞는 값이 없다. 억지로 {@code OTHER}를 넣으면 판별 실패 집계가
     * 오염된다.
     */
    private void recordLoadFailure(SchemaResult file, Exception cause) {
        Savepoint savepoint = null;
        try {
            savepoint = conn.setSavepoint();
            // 사유는 우리 처리 방식("롤백했다")이 아니라 실패 원인 원문이어야 한다 —
            // 담당자가 손댈 수 있는 것은 제약 위반이나 값 길이이지 savepoint가 아니다.
            loadFailure(new FailedAttachment(file.getAtchFileNm(), LoadStep.LOAD.code(),
                    null, Errors.describe(cause), file.getSourceBoard()));
            conn.releaseSavepoint(savepoint);
        } catch (Exception e) {
            rollbackQuietly(savepoint);
            System.out.println("[적재실패 기록 실패] " + file.getAtchFileNm() + ": "
                    + Errors.describe(e));
        }
    }

    /** 세이브포인트를 되돌린다 — 되돌리기까지 실패하면 원래 실패를 덮지 않도록 삼킨다. */
    private void rollbackQuietly(Savepoint savepoint) {
        if (savepoint == null) {
            return;
        }
        try {
            conn.rollback(savepoint);
        } catch (SQLException ignored) {
            // 원인은 이미 호출부가 출력했다
        }
    }

    /** 판별·추출 실패 건을 첨부 행으로만 남긴다(항목값 없음). */
    private void loadFailure(FailedAttachment failure) throws SQLException {
        SourceFileName.Parsed name = SourceFileName.parse(failure.fileName());
        prepareSlot(name, failure.board());

        try (PreparedStatement ps = conn.prepareStatement(INSERT_ATTACHMENT)) {
            ps.setInt(1, name.notiSn());
            ps.setInt(2, name.atchSn());
            ps.setString(3, failure.fileName());
            ps.setString(4, failure.filePath());
            ps.setString(5, STATUS_FAILED);
            ps.setString(6, failure.stage());
            ps.setString(7, failure.kind());
            ps.setString(8, failure.message());
            ps.setNull(9, Types.VARCHAR);        // EXCL_RSN_CTNT
            ps.setString(10, extensionOf(failure.fileName()));
            ps.setNull(11, Types.VARCHAR);       // ACTL_FILE_EXTN_NM — 판별에 실패했으면 알 수 없다
            ps.setString(12, DbStandard.yn(false));  // SCAN_YN — 위와 같은 이유로 단정하지 않는다
            ps.setNull(13, Types.VARCHAR);       // EXTC_ENGN_NM
            ps.setNull(14, Types.VARCHAR);       // NOTI_KND_CD — 제목을 못 읽었으니 분류할 수 없다
            ps.setNull(15, Types.VARCHAR);       // NOTI_NO
            ps.setNull(16, Types.DATE);          // NOTI_DT
            ps.setNull(17, Types.VARCHAR);       // NOTI_TTL
            ps.executeUpdate();
        }
    }

    /** 소문자 확장자(점 제외) — 추출에 실패해도 파일명만으로 알 수 있는 유일한 형식 정보다. */
    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? null : fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 게시물 행을 확보하고 같은 첨부의 이전 적재분을 지운다.
     *
     * @param board 수집처. null이면 기관·게시판을 비운 채로 게시물만 만든다 — 폴더 규약 밖에서
     *              온 파일(수동 수집분)도 첨부는 적재돼야 한다
     */
    private void prepareSlot(SourceFileName.Parsed name, AgencyRegistry.SourceBoard board)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_NOTICE)) {
            ps.setInt(1, name.notiSn());
            if (board == null) {
                ps.setNull(2, Types.INTEGER);
                ps.setNull(3, Types.VARCHAR);
            } else {
                ps.setInt(2, board.agncyNo());
                ps.setString(3, board.boardCd());
            }
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(DELETE_ATTACHMENT)) {
            ps.setInt(1, name.notiSn());
            ps.setInt(2, name.atchSn());
            ps.executeUpdate();
        }
    }

    /** 첨부 1행 삽입 — 파일 메타는 SchemaResult에서, 문서 메타는 첫 레코드에서 가져온다. */
    private void insertAttachment(SchemaResult file, SourceFileName.Parsed name,
                                  NoticeRecord meta) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_ATTACHMENT)) {
            int i = 1;
            ps.setInt(i++, name.notiSn());
            ps.setInt(i++, name.atchSn());
            ps.setString(i++, file.getAtchFileNm());
            ps.setString(i++, file.getAtchFilePath());
            ps.setString(i++, file.isExcluded() ? STATUS_SKIPPED : STATUS_OK);
            ps.setNull(i++, Types.VARCHAR);                    // FAIL_STEP_CD
            ps.setNull(i++, Types.VARCHAR);                    // FAIL_KND_CD
            ps.setNull(i++, Types.VARCHAR);                    // FAIL_MSG_CTNT
            ps.setString(i++, file.getExclRsnCtnt());
            ps.setString(i++, file.getFileExtnNm());
            ps.setString(i++, file.getActlFileExtnNm());
            ps.setString(i++, DbStandard.yn(file.isScanYn()));
            ps.setString(i++, file.getExtcEngnNm());
            ps.setString(i++, file.getNotiKndCd());
            ps.setString(i++, meta == null ? null : meta.notiNo());
            setDate(ps, i++, meta == null ? null : meta.notiYmd());
            ps.setString(i, meta == null ? null : meta.notiTtl());
            ps.executeUpdate();
        }
    }

    /**
     * 레코드마다 {@code DSPS_SN}을 1부터 부여하고 표준항목 값을 행으로 펼친다.
     *
     * <p>어느 필드가 행이 되는지는 {@link AttributeRows}가 정한다 — 적재하는 쪽과
     * "한 줄도 못 넣었다"를 판정하는 쪽이 같은 코드를 봐야 둘이 어긋나지 않는다.
     */
    private int insertAttributes(SourceFileName.Parsed name, List<AttributeRows.Split> split)
            throws SQLException {
        int inserted = 0;
        try (PreparedStatement ps = conn.prepareStatement(INSERT_ATTRIBUTE)) {
            int recordNo = 0;
            for (AttributeRows.Split record : split) {
                recordNo++;
                for (AttributeRows.Row row : record.items()) {
                    addAttribute(ps, name, recordNo, row.itemCd(), row.value());
                    inserted++;
                }
            }
            if (inserted > 0) {
                ps.executeBatch();
            }
        }
        return inserted;
    }

    /**
     * 항목값 1행을 배치에 추가한다.
     *
     * <p>{@code seq}는 늘 1이다 — 레코드의 필드가 맵이라 같은 항목이 두 번 담길 수 없다.
     * 컬럼과 PK 자리는 미리 잡아 뒀다. 당초/변경 대비표처럼 한 레코드에 같은 항목이 반복되는
     * 서식을 Mapper가 나중에 지원할 때, 스키마를 다시 손대지 않아도 되게 하기 위함이다.
     */
    private void addAttribute(PreparedStatement ps, SourceFileName.Parsed name,
                              int recordNo, String attrCode, String value) throws SQLException {
        ps.setInt(1, name.notiSn());
        ps.setInt(2, name.atchSn());
        ps.setInt(3, recordNo);
        ps.setString(4, attrCode);
        ps.setInt(5, 1);
        ps.setString(6, value);
        ps.addBatch();
    }

    /**
     * 표준항목으로 매핑되지 못한 값을 라벨 원문 그대로 남긴다.
     *
     * <p>{@code DSPS_SN}은 항목값과 같은 번호를 쓴다 — 같은 처분에서 나온 값이므로 나란히
     * 놓고 봐야 "이 처분에서 무엇이 표준항목으로 갔고 무엇이 라벨로 남았는지"가 읽힌다.
     *
     * <p>이 테이블에는 {@code OS_NOTI_ITEM_TC}로 가는 FK가 없다. 있으면 사전에 없는 라벨이
     * FK 위반을 내고, 배치 삽입이라 그 첨부의 항목값·이미지가 통째로 롤백된다.
     */
    private int insertLabels(SourceFileName.Parsed name, List<AttributeRows.Split> split)
            throws SQLException {
        int inserted = 0;
        try (PreparedStatement ps = conn.prepareStatement(INSERT_LABEL)) {
            int recordNo = 0;
            for (AttributeRows.Split record : split) {
                recordNo++;
                for (AttributeRows.LabelRow row : record.labels()) {
                    ps.setInt(1, name.notiSn());
                    ps.setInt(2, name.atchSn());
                    ps.setInt(3, recordNo);
                    ps.setString(4, row.itemLblNm());
                    ps.setString(5, row.value());
                    ps.addBatch();
                    inserted++;
                }
            }
            if (inserted > 0) {
                ps.executeBatch();
            }
        }
        return inserted;
    }

    /** 이미지는 처분 레코드가 아니라 첨부의 속성이다 — 파일에 딸린 순서대로 번호를 매긴다. */
    private int insertImages(SchemaResult file, SourceFileName.Parsed name) throws SQLException {
        int inserted = 0;
        try (PreparedStatement ps = conn.prepareStatement(INSERT_IMAGE)) {
            int imageNo = 0;
            for (RawImage image : file.getImages()) {
                if (image.getPath() == null) {
                    continue;
                }
                imageNo++;
                ps.setInt(1, name.notiSn());
                ps.setInt(2, name.atchSn());
                ps.setInt(3, imageNo);
                ps.setString(4, image.getName());
                ps.setString(5, image.getPath());
                ps.addBatch();
                inserted++;
            }
            if (inserted > 0) {
                ps.executeBatch();
            }
        }
        return inserted;
    }

    /** ISO 날짜 문자열을 DATE 파라미터로 바인딩한다. null·파싱 실패는 SQL NULL. */
    private static void setDate(PreparedStatement ps, int index, String isoDate) throws SQLException {
        if (isoDate == null) {
            ps.setNull(index, Types.DATE);
            return;
        }
        try {
            ps.setObject(index, LocalDate.parse(isoDate));
        } catch (Exception e) {
            // 정규화 실패 흔적이 남았다면(이론상 Mapper가 이미 걸러냄) NULL 허용
            ps.setNull(index, Types.DATE);
        }
    }

    /**
     * 첨부 1건을 적재한 결과.
     *
     * @param records OS_NOTI_ITEM_VAL_DTL에 넣은 항목값 행 수
     * @param labels  OS_NOTI_LBL_VAL_DTL에 넣은 라벨값 행 수
     * @param images  OS_ATCH_IMG_DTL에 넣은 이미지 행 수
     */
    public record Counts(int records, int labels, int images) {
    }

    /** 커넥션을 닫는다(try-with-resources 종료 시 호출). */
    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
