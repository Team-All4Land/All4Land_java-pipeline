package com.onnara.extract.db;

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
 * <p>실행: {@code mvn test -Dgroups=db
 * -Ddb.test.url=jdbc:postgresql://localhost:5432/extract
 * -Ddb.test.user=extract -Ddb.test.password=extract}
 *
 * <p>테스트가 쓰는 게시물 순번은 {@link #NOTICE_BASE} 이상의 고정 구간이다 — 크롤 순번
 * (83~21,751)이나 폴백 음수 구간과 겹치지 않아, 실 데이터가 든 DB에서도 안전하다.
 */
@Tag("db")
class DbLoaderIT {

    /** 테스트 전용 게시물 순번 구간 시작 — 실 데이터와 겹치지 않는 자리. */
    private static final int NOTICE_BASE = 900_000;

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
        // document_attributes가 attribute_defs를 참조하므로 적재 전에 사전이 있어야 한다
        ReferenceSync.sync(dataSource);
    }

    /** 테스트가 넣은 게시물을 지우고 풀을 닫는다(각 테스트 후). CASCADE로 하위가 함께 정리된다. */
    @AfterEach
    void tearDown() throws SQLException {
        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM notices WHERE notice_no >= ?")) {
            ps.setInt(1, NOTICE_BASE);
            ps.executeUpdate();
        }
        dataSource.close();
    }

    /** 사전이 DB로 동기화되고, 계열·주요 여부까지 실려 오는지 검증한다. */
    @Test
    void syncsDictionaryIntoReferenceTables() throws SQLException {
        assertEquals(40, count("SELECT count(*) FROM attribute_defs"));
        assertEquals(55, count("SELECT count(*) FROM notice_types"));
        assertEquals(6, count("SELECT count(*) FROM attribute_defs WHERE is_core"));

        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT series, value_type FROM attribute_defs WHERE attr_code = 'area'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("면적", rs.getString("series"));
            assertEquals("text", rs.getString("value_type"));
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
                     SELECT status_code, agency_name, doc_no, doc_date, title, record_count
                       FROM attachments WHERE notice_no = ? AND attach_no = ?""")) {
            ps.setInt(1, NOTICE_BASE + 1);
            ps.setInt(2, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("ok", rs.getString("status_code"));
                assertEquals("군산지방해양수산청", rs.getString("agency_name"));
                assertEquals("고시 제2026-47호", rs.getString("doc_no"));
                assertEquals("2026-06-17", rs.getDate("doc_date").toLocalDate().toString());
                assertEquals(1, rs.getInt("record_count"));
            }
        }

        assertEquals("367,120.2㎡", attribute(NOTICE_BASE + 1, "area"));
        assertEquals("군산시 비응로 107 인근 공유수면", attribute(NOTICE_BASE + 1, "location"));
        // 기간은 시작/종료가 daterange 리터럴 한 행으로 합쳐진다
        assertEquals("[2025-09-01,2028-08-31]", attribute(NOTICE_BASE + 1, "work_period"));
        // 문서 메타는 항목값으로 중복 저장하지 않는다
        assertNull(attribute(NOTICE_BASE + 1, "agency"));
        assertNull(attribute(NOTICE_BASE + 1, "notice_date"));

        assertEquals(1, count("SELECT count(*) FROM attachment_images WHERE notice_no = "
                + (NOTICE_BASE + 1)));
    }

    /** 같은 첨부를 재적재해도 어느 계층에도 중복이 생기지 않아야 한다(CASCADE 멱등성). */
    @Test
    void reloadingSameAttachmentIsIdempotent() throws SQLException {
        SchemaResult schema = sample(NOTICE_BASE + 2, 1, "900002_1_고시문.hml");

        try (DbLoader loader = new DbLoader(dataSource)) {
            loader.loadAll(List.of(schema), List.of());
            loader.loadAll(List.of(schema), List.of());
        }

        assertEquals(1, count("SELECT count(*) FROM attachments WHERE notice_no = "
                + (NOTICE_BASE + 2)));
        assertEquals(1, count("SELECT count(*) FROM attachment_images WHERE notice_no = "
                + (NOTICE_BASE + 2)));
        assertEquals(1, count("SELECT count(*) FROM document_attributes WHERE notice_no = "
                + (NOTICE_BASE + 2) + " AND attr_code = 'area'"));
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

        assertEquals(3, count("SELECT count(*) FROM attachments WHERE notice_no = " + notice));
        assertEquals(3, count("SELECT count(*) FROM document_attributes WHERE notice_no = "
                + notice + " AND attr_code = 'area'"));
    }

    /** 판별·추출 실패도 첨부 행으로 남아야 한다 — 성공만 적재하면 추출 0건 기관이 안 보인다. */
    @Test
    void recordsFailedAttachments() throws SQLException {
        DbLoader.FailedAttachment failure = new DbLoader.FailedAttachment(
                "900004_1_깨진문서.hwp", "추출", "ZIP_CORRUPT", "IOException: 중앙 디렉터리를 찾을 수 없음");

        try (DbLoader loader = new DbLoader(dataSource)) {
            loader.loadAll(List.of(), List.of(failure));
        }

        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT status_code, fail_stage, fail_kind, fail_message, ext_outer, record_count
                       FROM attachments WHERE file_name = ?""")) {
            ps.setString(1, "900004_1_깨진문서.hwp");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("failed", rs.getString("status_code"));
                assertEquals("추출", rs.getString("fail_stage"));
                assertEquals("ZIP_CORRUPT", rs.getString("fail_kind"));
                assertTrue(rs.getString("fail_message").contains("중앙 디렉터리"));
                assertEquals("hwp", rs.getString("ext_outer"), "확장자는 파일명만으로 알 수 있다");
                assertEquals(0, rs.getInt("record_count"));
            }
        }
    }

    /** 목록표 문서는 레코드마다 record_no가 갈려 값의 짝이 유지돼야 한다. */
    @Test
    void keepsValuePairingAcrossListTableRecords() throws SQLException {
        SchemaResult schema = new SchemaResult("900005_1_목록고시.hml", "hml", false, "hml-dom");
        schema.setNoticeNo(NOTICE_BASE + 5);
        schema.setAttachNo(1);
        schema.getRecords().add(record("갑 부두", "100㎡", "가나상사"));
        schema.getRecords().add(record("을 부두", "200㎡", "다라수산"));

        try (DbLoader loader = new DbLoader(dataSource)) {
            loader.loadAll(List.of(schema), List.of());
        }

        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT record_no, max(value) FILTER (WHERE attr_code = 'location') AS loc,
                            max(value) FILTER (WHERE attr_code = 'applicant_name') AS who
                       FROM document_attributes WHERE notice_no = ?
                      GROUP BY record_no ORDER BY record_no""")) {
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

    /** 항목값 하나를 읽는다(없으면 null). */
    private String attribute(int noticeNo, String attrCode) throws SQLException {
        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT value FROM document_attributes WHERE notice_no = ? AND attr_code = ?")) {
            ps.setInt(1, noticeNo);
            ps.setString(2, attrCode);
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

    /** 레코드 1건 + 이미지 1건을 담은 적재용 스키마 픽스처. */
    private static SchemaResult sample(int noticeNo, int attachNo, String sourceFile) {
        SchemaResult schema = new SchemaResult(sourceFile, "hml", false, "hml-dom");
        schema.setNoticeNo(noticeNo);
        schema.setAttachNo(attachNo);
        schema.getRecords().add(new NoticeRecord.Builder()
                .set("agency", "군산지방해양수산청")
                .set("notice_no", "고시 제2026-47호")
                .set("notice_date", "2026-06-17")
                .set("title", "공유수면 점용·사용 변경허가 고시")
                .set("signer", "군산지방해양수산청장")
                .set("approval_date", "2026-06-05")
                .set("location", "군산시 비응로 107 인근 공유수면")
                .set("area", "367,120.2㎡")
                .set("purpose", "청소년 해양종합레포츠 교육")
                .set("work_period_start", "2025-09-01")
                .set("work_period_end", "2028-08-31")
                .set("applicant_name", "한국해양소년단 전북연맹")
                .set("applicant_address", "군산시 비응로 107")
                .extra("특이사항", "없음")
                .build());

        RawImage image = new RawImage(sourceFile + "_img0.png", 100);
        image.setPath("/srv/extract/out/images/" + sourceFile + "_img0.png");
        schema.getImages().add(image);
        return schema;
    }

    /** 목록표 한 행에 해당하는 레코드. */
    private static NoticeRecord record(String location, String area, String name) {
        return new NoticeRecord.Builder()
                .set("location", location)
                .set("area", area)
                .set("applicant_name", name)
                .build();
    }
}
