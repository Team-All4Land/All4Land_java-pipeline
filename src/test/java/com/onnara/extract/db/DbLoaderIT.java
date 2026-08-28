package com.onnara.extract.db;

import com.onnara.extract.common.AgencyRegistry;
import com.onnara.extract.common.LoadStep;
import com.onnara.extract.common.NoticeTypes;
import com.onnara.extract.common.model.NoticeRecord;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.SchemaResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 PostgreSQL을 요구하는 통합 테스트 — 기본 빌드({@code mvn test})에서는 제외된다.
 *
 * <p>실행: {@code mvn test -Dgroups=db -DexcludedGroups=
 * -Ddb.test.url=jdbc:postgresql://localhost:5432/extract
 * -Ddb.test.user=extract -Ddb.test.password=extract}
 *
 * <p>{@code -DexcludedGroups=}를 빠뜨리면 pom의 기본 제외가 그대로 살아 한 건도 돌지 않는다 —
 * 실패가 아니라 "Tests run: 0"으로 조용히 끝나므로 통과한 것으로 오해하기 쉽다.
 *
 * <p>테스트가 쓰는 게시물 순번은 {@link #NOTICE_BASE} 이상의 고정 구간이다 — 크롤 순번
 * (83~21,751)이나 폴백 음수 구간과 겹치지 않아, 실 데이터가 든 DB에서도 안전하다.
 * 기관번호도 같은 이유로 {@link #AGENCY_BASE} 이상만 쓴다(실제는 1~74다).
 */
@Tag("db")
class DbLoaderIT {

    /** 테스트 전용 게시물 순번 구간 시작 — 실 데이터와 겹치지 않는 자리. */
    private static final int NOTICE_BASE = 900_000;

    /** 테스트 전용 기관번호 구간 시작 — 폴더에서 나온 실제 기관번호(1~74)와 겹치지 않는다. */
    private static final int AGENCY_BASE = 900_000;

    /** 게시 중인 게시판에서 온 파일의 수집처 픽스처. */
    private static final AgencyRegistry.SourceBoard OPEN_BOARD =
            new AgencyRegistry.SourceBoard(AGENCY_BASE + 1, "목포시청", "LOCL", "POST");

    /** 같은 기관의 지난 공고 게시판 — 기관은 같고 BBS_STTS_CD만 갈린다. */
    private static final AgencyRegistry.SourceBoard CLOSED_BOARD =
            new AgencyRegistry.SourceBoard(AGENCY_BASE + 1, "목포시청", "LOCL", "CLSD");

    private HikariDataSource dataSource;

    /** 테스트 DB 커넥션 풀을 열고 스키마 최신화 + 사전 동기화를 한다(각 테스트 전). */
    @BeforeEach
    void setUp() throws SQLException {
        String url = System.getProperty("db.test.url", "jdbc:postgresql://localhost:5432/extract");
        String user = System.getProperty("db.test.user", "extract");
        String password = System.getProperty("db.test.password", "extract");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        dataSource = new HikariDataSource(config);

        DbSchema.migrate(dataSource);
        // OS_NOTI_ITEM_VAL_DTL이 OS_NOTI_ITEM_TC을 참조하므로 적재 전에 사전이 있어야 한다
        ReferenceSync.sync(dataSource);
    }

    /**
     * 테스트가 넣은 게시물과 기관을 지우고 풀을 닫는다(각 테스트 후).
     * 첨부·이미지·항목값은 CASCADE로 함께 정리된다.
     *
     * <p>기관은 게시물보다 <b>나중에</b> 지운다 — {@code OS_NOTI_BAS.INSTT_SN}가 FK라 순서를
     * 뒤집으면 참조 위반으로 정리가 실패하고 다음 테스트가 더러운 DB를 물려받는다.
     */
    @AfterEach
    void tearDown() throws SQLException {
        try (var conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM OS_NOTI_BAS WHERE NOTI_SN >= ?")) {
                ps.setInt(1, NOTICE_BASE);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM OS_INSTT_BAS WHERE INSTT_SN >= ?")) {
                ps.setInt(1, AGENCY_BASE);
                ps.executeUpdate();
            }
        }
        dataSource.close();
    }

    /** 사전이 DB로 동기화되고, 계열·주요 여부까지 실려 오는지 검증한다. */
    @Test
    void syncsDictionaryIntoReferenceTables() throws SQLException {
        assertEquals(40, count("SELECT count(*) FROM OS_NOTI_ITEM_TC"));
        assertEquals(56, count("SELECT count(*) FROM OS_NOTI_KND_TC"));
        assertEquals(6, count("SELECT count(*) FROM OS_NOTI_ITEM_TC WHERE CORE_ITEM_YN = 'Y'"));

        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ITEM_SRS_NM, ITEM_VAL_TY_CD FROM OS_NOTI_ITEM_TC WHERE NOTI_ITEM_CD = 'AREA'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("면적", rs.getString("ITEM_SRS_NM"));
            assertEquals("TEXT", rs.getString("ITEM_VAL_TY_CD"));
        }
    }

    /** 첨부 메타·항목값·이미지가 3계층으로 저장되는지 검증한다. */
    @Test
    void insertsAttachmentWithAttributesAndImages() throws SQLException {
        SchemaResult schema = sample(NOTICE_BASE + 1, 1, "900001_1_고시문.hml");

        try (DbLoader loader = new DbLoader(dataSource)) {
            LoadStats stats = loader.loadAll(List.of(schema), List.of());
            assertEquals(1, stats.filesOk());
            assertEquals(0, stats.filesFailed());
            assertEquals(1, stats.imagesInserted());
        }

        // 문서 단위 메타는 첨부 컬럼으로 간다 — 항목값 행이 아니다
        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT PROC_STTS_CD, NOTI_KND_CD, NOTI_NO, NOTI_DT, NOTI_TTL,
                            ATCH_FILE_PATH
                       FROM OS_ATCH_FILE_DTL WHERE NOTI_SN = ? AND ATCH_SN = ?""")) {
            ps.setInt(1, NOTICE_BASE + 1);
            ps.setInt(2, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("OK", rs.getString("PROC_STTS_CD"));
                assertEquals("OCU_CHG_PRM", rs.getString("NOTI_KND_CD"),
                        "제목 \"공유수면 점용·사용 변경허가 고시\"는 변경허가로 분류돼야 한다");
                assertEquals("고시 제2026-47호", rs.getString("NOTI_NO"));
                assertEquals("2026-06-17", rs.getDate("NOTI_DT").toLocalDate().toString());
                assertEquals("/srv/input/900001_1_고시문.hml", rs.getString("ATCH_FILE_PATH"));
            }
        }

        // 처분 건수는 컬럼이 아니라 자식 테이블에서 나온다
        assertEquals(1, count("SELECT count(DISTINCT DSPS_SN) FROM OS_NOTI_ITEM_VAL_DTL"
                + " WHERE NOTI_SN = " + (NOTICE_BASE + 1)));

        assertEquals("367,120.2㎡", attribute(NOTICE_BASE + 1, "AREA"));
        assertEquals("군산시 비응로 107 인근 공유수면", attribute(NOTICE_BASE + 1, "LOC"));
        // 기간은 시작/종료가 daterange 리터럴 한 행으로 합쳐진다
        assertEquals("[2025-09-01,2028-08-31]", attribute(NOTICE_BASE + 1, "WORK_PRD"));
        // 문서 메타는 항목값으로 중복 저장하지 않는다
        assertNull(attribute(NOTICE_BASE + 1, "BODY_AGNCY_NM"),
                "본문 기관명은 적재하지 않는다 — 기관 추적은 OS_INSTT_BAS로 한다");
        assertNull(attribute(NOTICE_BASE + 1, "NOTI_DT"));

        assertEquals(1, count("SELECT count(*) FROM OS_ATCH_IMG_DTL WHERE NOTI_SN = "
                + (NOTICE_BASE + 1)));

        // 표준항목으로 매핑되지 못한 라벨은 버려지지 않고 라벨 테이블에 남는다
        assertEquals("없음", label(NOTICE_BASE + 1, "특이사항"));
    }

    /**
     * 사전에 없는 항목코드가 들어와도 첨부가 통째로 롤백되지 않아야 한다.
     *
     * <p>{@code map}과 {@code load}를 따로 돌릴 때 생기는 상황이다 — 옛 산출물이 옛 코드를
     * 들고 들어온다. 그것을 표준항목 행으로 만들면 {@code OS_NOTI_ITEM_TC} FK를 위반하고,
     * 배치 삽입이라 그 첨부의 항목값·이미지·첨부 행이 함께 사라진다. 라벨로 돌리면 값도
     * 남고 옛 코드가 집계에 떠서 산출물이 낡았다는 사실까지 드러난다.
     */
    @Test
    void unknownItemCodeBecomesLabelRowInsteadOfLosingTheAttachment() throws SQLException {
        int notiSn = NOTICE_BASE + 12;
        SchemaResult schema = sample(notiSn, 1, "900012_1_옛산출물.hml");
        schema.getRecords().add(new NoticeRecord.Builder()
                // 표준화 이전의 기간 항목코드 — 지금 사전에 없다
                .set("WORK_PERIOD", "2025-09-01 ~ 2028-08-31")
                .set("LOC", "여수시 돌산읍 인근 공유수면")
                .build());

        try (DbLoader loader = new DbLoader(dataSource)) {
            LoadStats stats = loader.loadAll(List.of(schema), List.of());
            assertEquals(1, stats.filesOk(), "옛 코드 한 줄에 첨부가 통째로 실패하면 안 된다");
            assertEquals(0, stats.filesFailed());
        }

        assertEquals(1, count("SELECT count(*) FROM OS_ATCH_FILE_DTL WHERE NOTI_SN = " + notiSn),
                "첨부 행이 살아남아야 한다");
        assertEquals("2025-09-01 ~ 2028-08-31", label(notiSn, "WORK_PERIOD"),
                "사전에 없는 코드는 라벨 원문 그대로 남는다");
        assertEquals("여수시 돌산읍 인근 공유수면", attributeOf(notiSn, 2, "LOC"),
                "같은 레코드의 표준항목은 정상 적재된다");
        assertEquals(0, count("SELECT count(*) FROM OS_NOTI_ITEM_VAL_DTL WHERE NOTI_SN = " + notiSn
                + " AND NOTI_ITEM_CD = 'WORK_PERIOD'"),
                "옛 코드가 표준항목 행으로 새면 안 된다");
    }

    /** 같은 첨부를 재적재해도 어느 계층에도 중복이 생기지 않아야 한다(CASCADE 멱등성). */
    @Test
    void reloadingSameAttachmentIsIdempotent() throws SQLException {
        SchemaResult schema = sample(NOTICE_BASE + 2, 1, "900002_1_고시문.hml");

        try (DbLoader loader = new DbLoader(dataSource)) {
            loader.loadAll(List.of(schema), List.of());
            loader.loadAll(List.of(schema), List.of());
        }

        assertEquals(1, count("SELECT count(*) FROM OS_ATCH_FILE_DTL WHERE NOTI_SN = "
                + (NOTICE_BASE + 2)));
        assertEquals(1, count("SELECT count(*) FROM OS_ATCH_IMG_DTL WHERE NOTI_SN = "
                + (NOTICE_BASE + 2)));
        assertEquals(1, count("SELECT count(*) FROM OS_NOTI_ITEM_VAL_DTL WHERE NOTI_SN = "
                + (NOTICE_BASE + 2) + " AND NOTI_ITEM_CD = 'AREA'"));
    }

    /**
     * 같은 게시물의 다른 첨부를 적재해도 앞선 첨부가 살아 있어야 한다.
     *
     * <p>멱등 단위를 게시물로 잡으면(초기 설계) 파일을 한 건씩 처리하는 도중 형제 첨부가
     * 함께 지워진다. 여수시청 6034처럼 첨부 5개가 각각 독립 처분인 게시물에서
     * 마지막 한 건만 남는 회귀를 막는다.
     */
    @Test
    void loadingSiblingAttachmentKeepsEarlierOnes() throws SQLException {
        int notice = NOTICE_BASE + 3;
        try (DbLoader loader = new DbLoader(dataSource)) {
            loader.loadAll(List.of(sample(notice, 1, "900003_1_고시문.hml")), List.of());
            loader.loadAll(List.of(sample(notice, 2, "900003_2_고시문.hml")), List.of());
            loader.loadAll(List.of(sample(notice, 3, "900003_3_고시문.hml")), List.of());
        }

        assertEquals(3, count("SELECT count(*) FROM OS_ATCH_FILE_DTL WHERE NOTI_SN = " + notice));
        assertEquals(3, count("SELECT count(*) FROM OS_NOTI_ITEM_VAL_DTL WHERE NOTI_SN = "
                + notice + " AND NOTI_ITEM_CD = 'AREA'"));
    }

    /** 판별·추출 실패도 첨부 행으로 남아야 한다 — 성공만 적재하면 추출 0건 기관이 안 보인다. */
    @Test
    void recordsFailedAttachments() throws SQLException {
        DbLoader.FailedAttachment failure = new DbLoader.FailedAttachment(
                "900004_1_깨진문서.hwp", "/srv/input/900004_1_깨진문서.hwp",
                LoadStep.EXTRACT.code(), "ZIP_CORRUPT", "IOException: 중앙 디렉터리를 찾을 수 없음",
                OPEN_BOARD);

        try (DbLoader loader = new DbLoader(dataSource)) {
            loader.loadAll(List.of(), List.of(failure));
        }

        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT PROC_STTS_CD, FAIL_STEP_CD, FAIL_KND_CD, FAIL_MSG_CTNT,
                            FILE_EXTN_NM, ATCH_FILE_PATH
                       FROM OS_ATCH_FILE_DTL WHERE ATCH_FILE_NM = ?""")) {
            ps.setString(1, "900004_1_깨진문서.hwp");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("FAIL", rs.getString("PROC_STTS_CD"));
                assertEquals("EXTC", rs.getString("FAIL_STEP_CD"));
                assertEquals("ZIP_CORRUPT", rs.getString("FAIL_KND_CD"));
                assertTrue(rs.getString("FAIL_MSG_CTNT").contains("중앙 디렉터리"));
                assertEquals("hwp", rs.getString("FILE_EXTN_NM"), "확장자는 파일명만으로 알 수 있다");
                assertEquals("/srv/input/900004_1_깨진문서.hwp", rs.getString("ATCH_FILE_PATH"));
            }
        }

        assertEquals(0, count("SELECT count(*) FROM OS_NOTI_ITEM_VAL_DTL WHERE NOTI_SN = "
                + (NOTICE_BASE + 4)), "실패 건은 항목값을 남기지 않는다");

        // 실패 건에도 기관이 붙어야 한다 — 안 붙이면 그 기관의 실패가 "기관 미상"으로 새어
        // 기관별 수집률이 실제보다 좋아 보인다
        assertEquals(AGENCY_BASE + 1, agencyOf(NOTICE_BASE + 4));
    }

    /**
     * 적재 자체가 넘어진 첨부도 흔적을 남겨야 한다.
     *
     * <p>값 하나가 컬럼 길이를 넘겨 첨부 INSERT가 죽는 상황이다. 롤백만 하고 끝내면 그 첨부는
     * DB에서 <b>수집조차 안 된 파일과 구별되지 않는다</b> — 콘솔을 놓친 순간 영영 모른다.
     * 항목값을 뺀 최소 행은 같은 이유로 죽지 않으므로, 그 자리에 다시 넣어 사실만은 남긴다.
     */
    @Test
    void recordsAttachmentsThatFailedToLoad() throws SQLException {
        SchemaResult doomed = sample(NOTICE_BASE + 10, 1, "900010_1_고시문.hml", OPEN_BOARD);
        // NOTI_TTL은 D_TTL(VARCHAR(500))이다 — 600자를 넣으면 첨부 INSERT가 반드시 죽는다
        doomed.getRecords().set(0, new NoticeRecord.Builder()
                .set("NOTI_TTL", "가".repeat(600))
                .build());
        SchemaResult healthy = sample(NOTICE_BASE + 11, 1, "900011_1_고시문.hml", OPEN_BOARD);

        try (DbLoader loader = new DbLoader(dataSource)) {
            LoadStats stats = loader.loadAll(List.of(doomed, healthy), List.of());
            assertEquals(1, stats.filesFailed());
            assertEquals(1, stats.filesOk(), "한 건이 죽어도 나머지는 적재된다(savepoint 격리)");
        }

        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT PROC_STTS_CD, FAIL_STEP_CD, FAIL_KND_CD, FAIL_MSG_CTNT, NOTI_TTL
                       FROM OS_ATCH_FILE_DTL WHERE NOTI_SN = ? AND ATCH_SN = ?""")) {
            ps.setInt(1, NOTICE_BASE + 10);
            ps.setInt(2, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "적재에 실패해도 첨부 행은 남아야 한다");
                assertEquals("FAIL", rs.getString("PROC_STTS_CD"));
                assertEquals("LOAD", rs.getString("FAIL_STEP_CD"),
                        "산출물 저장(SAVE)이 아니라 DB 적재에서 넘어진 것이다");
                assertNull(rs.getString("FAIL_KND_CD"),
                        "FailureKind는 판별·추출의 축이라 적재 실패에 들어맞는 값이 없다");
                assertTrue(rs.getString("FAIL_MSG_CTNT") != null
                                && !rs.getString("FAIL_MSG_CTNT").isBlank(),
                        "사유는 우리 처리 방식이 아니라 실패 원인 원문이어야 한다");
                assertNull(rs.getString("NOTI_TTL"), "최소 행이므로 문서 메타는 비어 있다");
            }
        }

        assertEquals(0, count("SELECT count(*) FROM OS_NOTI_ITEM_VAL_DTL WHERE NOTI_SN = "
                + (NOTICE_BASE + 10)), "적재 실패 건은 항목값을 남기지 않는다");
        assertEquals(1, count("SELECT count(*) FROM OS_ATCH_FILE_DTL WHERE NOTI_SN = "
                + (NOTICE_BASE + 11)), "같은 배치의 정상 첨부는 영향받지 않는다");
    }

    /**
     * SKIP은 <b>항목값</b> 적재제외다 — 파일을 통째로 빼는 것이 아니다.
     *
     * <p>코드값 이름이 그냥 "적재제외"였을 때 실제로 "아예 안 넣는다"로 읽혔다. 첨부 행과
     * 이미지는 그대로 들어가고 항목값·라벨값만 빠진다는 것을 여기서 못 박는다.
     */
    @Test
    void skipLeavesAttachmentAndImagesButNoValues() throws SQLException {
        SchemaResult schema = sample(NOTICE_BASE + 12, 1, "900012_1_이용안내.pdf", OPEN_BOARD);
        schema.setExclRsnCtnt("본문 8,355자 (임계 5,000자 초과)");

        try (DbLoader loader = new DbLoader(dataSource)) {
            LoadStats stats = loader.loadAll(List.of(schema), List.of());
            assertEquals(1, stats.filesSkipped());
            assertEquals(0, stats.filesOk());
            assertEquals(1, stats.imagesInserted(), "이미지는 적재제외 대상이 아니다");
        }

        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT PROC_STTS_CD, EXCL_RSN_CTNT, NOTI_TTL
                       FROM OS_ATCH_FILE_DTL WHERE NOTI_SN = ? AND ATCH_SN = ?""")) {
            ps.setInt(1, NOTICE_BASE + 12);
            ps.setInt(2, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "적재제외 첨부도 행으로 남는다");
                assertEquals("SKIP", rs.getString("PROC_STTS_CD"));
                assertTrue(rs.getString("EXCL_RSN_CTNT").contains("8,355자"),
                        "사유가 아니라 측정값이 남아야 임계 재보정에 쓸 수 있다");
                assertEquals("공유수면 점용·사용 변경허가 고시", rs.getString("NOTI_TTL"),
                        "추출·매핑은 정상으로 끝났으므로 문서 메타는 채워진다");
            }
        }

        assertEquals(1, count("SELECT count(*) FROM OS_ATCH_IMG_DTL WHERE NOTI_SN = "
                + (NOTICE_BASE + 12)), "이미지는 들어간다");
        assertEquals(0, count("SELECT count(*) FROM OS_NOTI_ITEM_VAL_DTL WHERE NOTI_SN = "
                + (NOTICE_BASE + 12)), "항목값만 빠진다");
        assertEquals(0, count("SELECT count(*) FROM OS_NOTI_LBL_VAL_DTL WHERE NOTI_SN = "
                + (NOTICE_BASE + 12)), "라벨값도 함께 빠진다");
    }

    /** 목록표 문서는 레코드마다 DSPS_SN이 갈려 값의 짝이 유지돼야 한다. */
    @Test
    void keepsValuePairingAcrossListTableRecords() throws SQLException {
        SchemaResult schema = new SchemaResult("900005_1_목록고시.hml", "hml", false, "hml-dom");
        schema.setNotiSn(NOTICE_BASE + 5);
        schema.setAtchSn(1);
        schema.getRecords().add(record("갑 부두", "100㎡", "가나상사"));
        schema.getRecords().add(record("을 부두", "200㎡", "다라수산"));

        try (DbLoader loader = new DbLoader(dataSource)) {
            loader.loadAll(List.of(schema), List.of());
        }

        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT DSPS_SN, max(ITEM_VAL_CTNT) FILTER (WHERE NOTI_ITEM_CD = 'LOC') AS loc,
                            max(ITEM_VAL_CTNT) FILTER (WHERE NOTI_ITEM_CD = 'APLC_NM') AS who
                       FROM OS_NOTI_ITEM_VAL_DTL WHERE NOTI_SN = ?
                      GROUP BY DSPS_SN ORDER BY DSPS_SN""")) {
            ps.setInt(1, NOTICE_BASE + 5);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("갑 부두", rs.getString("loc"));
                assertEquals("가나상사", rs.getString("who"), "1번 레코드의 장소·성명이 짝을 유지해야 함");
                assertTrue(rs.next());
                assertEquals("을 부두", rs.getString("loc"));
                assertEquals("다라수산", rs.getString("who"));
            }
        }
    }

    /**
     * 중간 처분의 값이 비어도 나머지 처분의 짝이 어긋나면 안 된다.
     *
     * <p>{@code DSPS_SN}이 존재하는 이유가 바로 이것이다. 항목별 순번({@code RPT_SN})만으로
     * 짝을 맞추면 면적이 빈 처분 하나 때문에 뒤따르는 면적이 한 칸씩 밀려 엉뚱한 장소에 붙는다.
     * 구멍은 예외가 아니다 — 전역 출현율이 60%를 넘는 표준항목은 여섯 개뿐이다.
     */
    @Test
    void keepsPairingWhenAMiddleDispositionHasNoValue() throws SQLException {
        int notice = NOTICE_BASE + 11;
        SchemaResult schema = new SchemaResult("900011_1_목록고시.hml", "hml", false, "hml-dom");
        schema.setNotiSn(notice);
        schema.setAtchSn(1);
        schema.getRecords().add(record("인천", "50㎡", "가나상사"));
        schema.getRecords().add(record("군산", null, "다라수산"));
        schema.getRecords().add(record("여수", "100㎡", "마바해운"));

        try (DbLoader loader = new DbLoader(dataSource)) {
            loader.loadAll(List.of(schema), List.of());
        }

        assertEquals(3, count("SELECT count(DISTINCT DSPS_SN) FROM OS_NOTI_ITEM_VAL_DTL"
                + " WHERE NOTI_SN = " + notice));
        // 면적은 두 행뿐인데도 장소와의 짝이 유지된다
        assertEquals(2, count("SELECT count(*) FROM OS_NOTI_ITEM_VAL_DTL"
                + " WHERE NOTI_SN = " + notice + " AND NOTI_ITEM_CD = 'AREA'"));

        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT max(ITEM_VAL_CTNT) FILTER (WHERE NOTI_ITEM_CD = 'AREA') AS area
                       FROM OS_NOTI_ITEM_VAL_DTL
                      WHERE NOTI_SN = ? AND DSPS_SN = (
                            SELECT DSPS_SN FROM OS_NOTI_ITEM_VAL_DTL
                             WHERE NOTI_SN = ? AND NOTI_ITEM_CD = 'LOC' AND ITEM_VAL_CTNT = '여수')""")) {
            ps.setInt(1, notice);
            ps.setInt(2, notice);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("100㎡", rs.getString("area"), "여수의 면적은 100㎡여야 한다");
            }
        }
    }

    /**
     * 주요항목 요약 뷰는 첨부파일 1건을 한 행으로 낸다.
     *
     * <p>처분이 여럿인 목록표 문서도 한 행이다 — 여섯 항목이 처분별로 갈리지 않고 한 칸에
     * 합쳐진다. 그 합침이 실제로 일어나는지, 그리고 이미지 개수가 처분 수만큼 부풀지 않는지가
     * 이 테스트가 지키는 두 가지다. 뒤엣것은 서브쿼리를 먼저 접지 않으면 바로 깨진다.
     */
    @Test
    void coreItemViewSummarisesEachAttachmentAsOneRow() throws SQLException {
        int single = NOTICE_BASE + 20;
        int listed = NOTICE_BASE + 21;

        SchemaResult one = sample(single, 1, "900020_1_고시문.hml", OPEN_BOARD);

        SchemaResult many = new SchemaResult("900021_1_목록고시.hml", "hml", false, "hml-dom");
        many.setSourceBoard(OPEN_BOARD);
        many.setNotiSn(listed);
        many.setAtchSn(1);
        many.getRecords().add(record("갑 부두", "100㎡", "가나상사"));
        many.getRecords().add(record("을 부두", "200㎡", "다라수산"));
        // 처분 2 × 이미지 2 — 서브쿼리를 먼저 접지 않으면 여기서 4가 나온다
        many.getImages().add(image("900021_1_목록고시.hml", 0));
        many.getImages().add(image("900021_1_목록고시.hml", 1));

        try (DbLoader loader = new DbLoader(dataSource)) {
            loader.loadAll(List.of(one, many), List.of());
        }

        assertEquals(1, count("SELECT count(*) FROM OS_ATCH_CORE_ITEM_VW WHERE NOTI_SN = " + single));
        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT INSTT_NM, NOTI_KND_NM, LOC_VAL_CTNT, AREA_VAL_CTNT, PRPS_VAL_CTNT,
                            APLC_NM_VAL_CTNT, WORK_PRD_VAL_CTNT, APLC_ADDR_VAL_CTNT, ATCH_IMG_CNT
                       FROM OS_ATCH_CORE_ITEM_VW WHERE NOTI_SN = ? AND ATCH_SN = ?""")) {
            ps.setInt(1, single);
            ps.setInt(2, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("목포시청", rs.getString("INSTT_NM"));
                assertEquals("변경허가", rs.getString("NOTI_KND_NM"),
                        "공고종류는 코드가 아니라 이름으로 편다");
                assertEquals("군산시 비응로 107 인근 공유수면", rs.getString("LOC_VAL_CTNT"));
                assertEquals("367,120.2㎡", rs.getString("AREA_VAL_CTNT"));
                assertEquals("청소년 해양종합레포츠 교육", rs.getString("PRPS_VAL_CTNT"));
                assertEquals("한국해양소년단 전북연맹", rs.getString("APLC_NM_VAL_CTNT"));
                assertEquals("[2025-09-01,2028-08-31]", rs.getString("WORK_PRD_VAL_CTNT"),
                        "기간은 daterange 리터럴 원문 그대로 온다 — 비교는 FC_OS_ISO_DATERANGE()로 한다");
                assertEquals("군산시 비응로 107", rs.getString("APLC_ADDR_VAL_CTNT"));
                assertEquals(1, rs.getInt("ATCH_IMG_CNT"));
            }
        }

        assertEquals(1, count("SELECT count(*) FROM OS_ATCH_CORE_ITEM_VW WHERE NOTI_SN = " + listed),
                "처분이 둘이어도 첨부가 하나면 한 행이다");
        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT LOC_VAL_CTNT, APLC_NM_VAL_CTNT, ATCH_IMG_CNT"
                             + " FROM OS_ATCH_CORE_ITEM_VW WHERE NOTI_SN = ?")) {
            ps.setInt(1, listed);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("갑 부두 | 을 부두", rs.getString("LOC_VAL_CTNT"),
                        "처분별 값은 첨부 단위로 합쳐진다 — 짝은 여기서 보이지 않는다");
                assertEquals("가나상사 | 다라수산", rs.getString("APLC_NM_VAL_CTNT"));
                assertEquals(2, rs.getInt("ATCH_IMG_CNT"),
                        "이미지 개수가 처분 수만큼 부풀면 안 된다 — 2이지 4가 아니다");
            }
        }
    }

    /**
     * 뷰가 담는 행의 범위 — 처리상태가 정상(OK)인 첨부는 전부, 실패·적재제외는 하나도.
     *
     * <p>주요 6항목을 하나도 못 뽑은 첨부도 남는다는 것이 핵심이다. 그 행까지 빼면 "이 파일에서
     * 주요항목을 못 뽑았다"와 "이 파일은 아예 안 들어왔다"가 둘 다 "행 없음"이 돼 구별되지
     * 않는다 — 크롤로그가 성공도 행으로 남기는 것과 같은 이유다.
     */
    @Test
    void coreItemViewKeepsOkAttachmentsAndDropsFailedOrSkipped() throws SQLException {
        int bareSn = NOTICE_BASE + 22;
        int skippedSn = NOTICE_BASE + 23;
        int failedSn = NOTICE_BASE + 24;

        // 표준항목이지만 주요항목은 아닌 값 하나만 든 첨부 — 취소 고시문이 이렇게 생겼다
        SchemaResult bare = new SchemaResult("900022_1_취소고시.hml", "hml", false, "hml-dom");
        bare.setSourceBoard(OPEN_BOARD);
        bare.setNotiSn(bareSn);
        bare.setAtchSn(1);
        bare.getRecords().add(new NoticeRecord.Builder().set("APV_NO", "제2026-3호").build());

        SchemaResult excluded = sample(skippedSn, 1, "900023_1_이용안내.pdf", OPEN_BOARD);
        excluded.setExclRsnCtnt("본문 8,355자 (임계 5,000자 초과)");

        DbLoader.FailedAttachment failure = new DbLoader.FailedAttachment(
                "900024_1_깨진문서.hwp", "/srv/input/900024_1_깨진문서.hwp",
                LoadStep.EXTRACT.code(), "ZIP_CORRUPT", "IOException: 중앙 디렉터리를 찾을 수 없음",
                OPEN_BOARD);

        try (DbLoader loader = new DbLoader(dataSource)) {
            loader.loadAll(List.of(bare, excluded), List.of(failure));
        }

        assertEquals("제2026-3호", attribute(bareSn, "APV_NO"), "주요항목이 아닌 값은 들어가 있다");
        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT LOC_VAL_CTNT, AREA_VAL_CTNT, PRPS_VAL_CTNT, APLC_NM_VAL_CTNT,
                            WORK_PRD_VAL_CTNT, APLC_ADDR_VAL_CTNT, ATCH_IMG_CNT
                       FROM OS_ATCH_CORE_ITEM_VW WHERE NOTI_SN = ?""")) {
            ps.setInt(1, bareSn);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "주요항목을 하나도 못 뽑아도 정상 첨부는 뷰에 남는다");
                assertNull(rs.getString("LOC_VAL_CTNT"));
                assertNull(rs.getString("AREA_VAL_CTNT"));
                assertNull(rs.getString("PRPS_VAL_CTNT"));
                assertNull(rs.getString("APLC_NM_VAL_CTNT"));
                assertNull(rs.getString("WORK_PRD_VAL_CTNT"));
                assertNull(rs.getString("APLC_ADDR_VAL_CTNT"));
                assertEquals(0, rs.getInt("ATCH_IMG_CNT"));
            }
        }

        assertEquals(0, count("SELECT count(*) FROM OS_ATCH_CORE_ITEM_VW WHERE NOTI_SN = "
                + skippedSn), "적재제외(SKIP) 첨부는 뷰에 담지 않는다");
        assertEquals(0, count("SELECT count(*) FROM OS_ATCH_CORE_ITEM_VW WHERE NOTI_SN = "
                + failedSn), "추출 실패(FAIL) 첨부는 뷰에 담지 않는다");
    }

    /**
     * 입력 폴더에서 읽은 기관과 게시판이 OS_INSTT_BAS·OS_NOTI_BAS로 들어가야 한다.
     *
     * <p>같은 기관이 게시판을 둘 운영하면 기관 행은 하나고 {@code BBS_STTS_CD}만 갈린다 —
     * 기관을 쪼개면 "목포시청 수집 현황"이 두 줄로 나뉜다.
     */
    @Test
    void fillsAgencyAndBoardFromTheSourceFolder() throws SQLException {
        SchemaResult open = sample(NOTICE_BASE + 6, 1, "900006_1_고시문.hml", OPEN_BOARD);
        SchemaResult closed = sample(NOTICE_BASE + 7, 1, "900007_1_고시문.hml", CLOSED_BOARD);

        try (DbLoader loader = new DbLoader(dataSource)) {
            LoadStats stats = loader.loadAll(List.of(open, closed), List.of());
            assertEquals(1, stats.agenciesUpserted(), "게시판이 둘이어도 기관은 하나다");
        }

        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT INSTT_NM, INSTT_KND_CD FROM OS_INSTT_BAS WHERE INSTT_SN = ?")) {
            ps.setInt(1, AGENCY_BASE + 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("목포시청", rs.getString("INSTT_NM"));
                assertEquals("LOCL", rs.getString("INSTT_KND_CD"));
            }
        }

        assertEquals(AGENCY_BASE + 1, agencyOf(NOTICE_BASE + 6));
        assertEquals(AGENCY_BASE + 1, agencyOf(NOTICE_BASE + 7));
        assertEquals("POST", boardOf(NOTICE_BASE + 6));
        assertEquals("CLSD", boardOf(NOTICE_BASE + 7));
    }

    /**
     * 수집처를 모르는 파일도 첨부는 적재되고 기관만 NULL이어야 한다.
     *
     * <p>{@code map} → {@code load}로 나눠 도는 경로와 손으로 모은 샘플이 여기 해당한다.
     * 기관이 없다고 적재를 거르면 그 파일의 항목값이 통째로 사라진다.
     */
    @Test
    void loadsAttachmentsWithoutAnAgency() throws SQLException {
        try (DbLoader loader = new DbLoader(dataSource)) {
            LoadStats stats = loader.loadAll(
                    List.of(sample(NOTICE_BASE + 8, 1, "900008_1_고시문.hml")), List.of());
            assertEquals(0, stats.agenciesUpserted());
            assertEquals(1, stats.filesOk());
        }

        assertEquals(0, agencyOf(NOTICE_BASE + 8), "기관 미상은 NULL로 남는다");
        assertEquals(1, count("SELECT count(*) FROM OS_ATCH_FILE_DTL WHERE NOTI_SN = "
                + (NOTICE_BASE + 8)));
    }

    /**
     * 첨부가 0건인 기관도 행으로 남아야 한다 — 그래야 "긁었는데 아무것도 못 건진 기관"이
     * DB에서 보인다. 수집 누락과 애초에 자료가 없던 기관을 구분하려면 필요하다.
     */
    @Test
    void registersAgenciesThatYieldedNoAttachments() throws SQLException {
        try (DbLoader loader = new DbLoader(dataSource)) {
            assertEquals(1, loader.loadAgencies(
                    List.of(new AgencyRegistry.Agency(AGENCY_BASE + 2, "포항시청", "LOCL"))));
        }

        assertEquals(1, count("SELECT count(*) FROM OS_INSTT_BAS WHERE INSTT_SN = "
                + (AGENCY_BASE + 2)));
        assertEquals(0, count("SELECT count(*) FROM OS_NOTI_BAS WHERE INSTT_SN = "
                + (AGENCY_BASE + 2)));
    }

    /**
     * 수집처를 모르는 재적재가 이미 채운 기관을 지우면 안 된다.
     *
     * <p>{@code pipeline}으로 넣은 뒤 같은 스키마 JSON을 {@code load}로 다시 밀면 폴더 정보가
     * 없다. {@code DO NOTHING}이나 무조건 덮어쓰기였다면 여기서 기관이 NULL로 되돌아간다.
     */
    @Test
    void reloadingWithoutFolderInfoKeepsTheAgency() throws SQLException {
        try (DbLoader loader = new DbLoader(dataSource)) {
            loader.loadAll(List.of(
                    sample(NOTICE_BASE + 9, 1, "900009_1_고시문.hml", OPEN_BOARD)), List.of());
            loader.loadAll(List.of(
                    sample(NOTICE_BASE + 9, 1, "900009_1_고시문.hml")), List.of());
        }

        assertEquals(AGENCY_BASE + 1, agencyOf(NOTICE_BASE + 9));
        assertEquals("POST", boardOf(NOTICE_BASE + 9));
    }

    /** 게시물의 기관번호를 읽는다(NULL이면 0). */
    private int agencyOf(int notiSn) throws SQLException {
        return count("SELECT coalesce(INSTT_SN, 0) FROM OS_NOTI_BAS WHERE NOTI_SN = " + notiSn);
    }

    /** 게시물의 게시판 구분을 읽는다(없으면 null). */
    private String boardOf(int notiSn) throws SQLException {
        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT BBS_STTS_CD FROM OS_NOTI_BAS WHERE NOTI_SN = ?")) {
            ps.setInt(1, notiSn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 항목값 하나를 읽는다(없으면 null). */
    private String attribute(int notiSn, String attrCode) throws SQLException {
        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ITEM_VAL_CTNT FROM OS_NOTI_ITEM_VAL_DTL WHERE NOTI_SN = ? AND NOTI_ITEM_CD = ?")) {
            ps.setInt(1, notiSn);
            ps.setString(2, attrCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 라벨값 한 건을 읽는다 — 없으면 null. */
    private String label(int notiSn, String itemLblNm) throws SQLException {
        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ITEM_VAL_CTNT FROM OS_NOTI_LBL_VAL_DTL WHERE NOTI_SN = ? AND ITEM_LBL_NM = ?")) {
            ps.setInt(1, notiSn);
            ps.setString(2, itemLblNm);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 특정 처분(DSPS_SN)의 항목값 한 건을 읽는다 — 없으면 null. */
    private String attributeOf(int notiSn, int dspsSn, String attrCode) throws SQLException {
        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT ITEM_VAL_CTNT FROM OS_NOTI_ITEM_VAL_DTL"
                     + " WHERE NOTI_SN = ? AND DSPS_SN = ? AND NOTI_ITEM_CD = ?")) {
            ps.setInt(1, notiSn);
            ps.setInt(2, dspsSn);
            ps.setString(3, attrCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 집계 쿼리 한 줄을 읽는다. */
    private int count(String sql) throws SQLException {
        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    /** 수집처를 모르는(폴더 규약 밖) 적재용 스키마 픽스처. */
    private static SchemaResult sample(int notiSn, int atchSn, String atchFileNm) {
        return sample(notiSn, atchSn, atchFileNm, null);
    }

    /** 레코드 1건 + 이미지 1건을 담은 적재용 스키마 픽스처. */
    private static SchemaResult sample(int notiSn, int atchSn, String atchFileNm,
                                       AgencyRegistry.SourceBoard board) {
        SchemaResult schema = new SchemaResult(atchFileNm, "hml", false, "hml-dom");
        schema.setAtchFilePath("/srv/input/" + atchFileNm);
        schema.setSourceBoard(board);
        schema.setNotiSn(notiSn);
        schema.setAtchSn(atchSn);
        // Mapper가 하는 것과 같은 방식으로 분류한다 — 픽스처에 코드를 손으로 박으면
        // 규칙이 바뀌어도 테스트가 계속 통과해 회귀를 놓친다
        schema.setNotiKndCd(
                NoticeTypes.classify("공유수면 점용·사용 변경허가 고시").orElseThrow());
        schema.getRecords().add(new NoticeRecord.Builder()
                .set("BODY_AGNCY_NM", "군산지방해양수산청")
                .set("NOTI_NO", "고시 제2026-47호")
                .set("NOTI_DT", "2026-06-17")
                .set("NOTI_TTL", "공유수면 점용·사용 변경허가 고시")
                .set("NOTI_PSN", "군산지방해양수산청장")
                .set("APV_DT", "2026-06-05")
                .set("LOC", "군산시 비응로 107 인근 공유수면")
                .set("AREA", "367,120.2㎡")
                .set("PRPS", "청소년 해양종합레포츠 교육")
                .set("WORK_PRD_ST", "2025-09-01")
                .set("WORK_PRD_EN", "2028-08-31")
                .set("APLC_NM", "한국해양소년단 전북연맹")
                .set("APLC_ADDR", "군산시 비응로 107")
                .extra("특이사항", "없음")
                .build());

        RawImage image = new RawImage(atchFileNm + "_img0.png", 100);
        image.setPath("/srv/extract/out/images/" + atchFileNm + "_img0.png");
        schema.getImages().add(image);
        return schema;
    }

    /** 첨부 하나에 딸린 이미지 한 건. */
    private static RawImage image(String atchFileNm, int index) {
        RawImage image = new RawImage(atchFileNm + "_img" + index + ".png", 100);
        image.setPath("/srv/extract/out/images/" + atchFileNm + "_img" + index + ".png");
        return image;
    }

    /** 목록표 한 행에 해당하는 레코드. */
    private static NoticeRecord record(String location, String area, String name) {
        return new NoticeRecord.Builder()
                .set("LOC", location)
                .set("AREA", area)
                .set("APLC_NM", name)
                .build();
    }
}
