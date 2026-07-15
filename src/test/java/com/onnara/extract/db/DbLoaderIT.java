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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 PostgreSQL을 요구하는 통합 테스트 — 기본 빌드({@code mvn test})에서는 제외된다.
 *
 * <p>실행: {@code mvn test -Dgroups=db
 * -Ddb.test.url=jdbc:postgresql://localhost:5432/extract
 * -Ddb.test.user=extract -Ddb.test.password=extract}
 */
@Tag("db")
class DbLoaderIT {

    private HikariDataSource dataSource;

    /** 테스트 DB 커넥션 풀을 열고 스키마를 마이그레이션한다(각 테스트 전). */
    @BeforeEach
    void setUp() {
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
    }

    /** 테스트가 넣은 it-* 행을 지우고 풀을 닫는다(각 테스트 후). */
    @AfterEach
    void tearDown() throws SQLException {
        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM documents WHERE source_file LIKE 'it-%'")) {
            ps.executeUpdate();
        }
        dataSource.close();
    }

    /** documents·ref_files가 삽입되고 날짜·extras·조인이 기대대로 저장되는지 검증한다. */
    @Test
    void insertsDocumentsAndRefFiles() throws SQLException {
        SchemaResult schema = sample("it-doc1.hml");

        try (DbLoader loader = new DbLoader(dataSource)) {
            LoadStats stats = loader.loadAll(List.of(schema));
            assertEquals(1, stats.filesOk());
            assertEquals(0, stats.filesFailed());
            assertEquals(1, stats.documentsInserted());
            assertEquals(1, stats.refFilesInserted());
        }

        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT agency, notice_no, notice_date, extras FROM documents WHERE source_file = ?")) {
            ps.setString(1, "it-doc1.hml");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("군산지방해양수산청", rs.getString("agency"));
                assertEquals("2026-06-17", rs.getDate("notice_date").toLocalDate().toString());
                assertTrue(rs.getString("extras").contains("특이사항"));
            }
        }

        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM ref_files rf JOIN documents d ON rf.document_seq = d.seq "
                             + "WHERE d.source_file = ?")) {
            ps.setString(1, "it-doc1.hml");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    /** 같은 source_file 재적재 시 행이 중복되지 않고(멱등) ref_files도 CASCADE로 정리되는지 검증한다. */
    @Test
    void reloadingSameSourceFileIsIdempotent() throws SQLException {
        SchemaResult schema = sample("it-doc2.hml");

        try (DbLoader loader = new DbLoader(dataSource)) {
            loader.loadAll(List.of(schema));
            loader.loadAll(List.of(schema));
        }

        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM documents WHERE source_file = ?")) {
            ps.setString(1, "it-doc2.hml");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "재적재 후에도 행이 1개여야 함(멱등)");
            }
        }

        // CASCADE 확인: ref_files도 중복되지 않아야 함
        try (var conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM ref_files rf JOIN documents d ON rf.document_seq = d.seq "
                             + "WHERE d.source_file = ?")) {
            ps.setString(1, "it-doc2.hml");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    /** 레코드 1건 + 이미지 1건을 담은 적재용 스키마 픽스처를 만든다. */
    private static SchemaResult sample(String sourceFile) {
        Map<String, String> extras = new LinkedHashMap<>();
        extras.put("특이사항", "없음");
        NoticeRecord record = new NoticeRecord(
                "군산지방해양수산청", "고시 제2026-47호", "2026-06-17", "공유수면 점용·사용 변경허가 고시",
                "군산지방해양수산청장", null, "2026-06-05", "군산시 비응로 107 인근 공유수면", "367,120.2㎡",
                "청소년 해양종합레포츠 교육", "2025-09-01", "2028-08-31",
                "한국해양소년단 전북연맹", "군산시 비응로 107", null, extras);

        SchemaResult schema = new SchemaResult(sourceFile, "hml", false, "hml-dom");
        schema.getRecords().add(record);
        RawImage image = new RawImage(sourceFile + "_img0.png", 100);
        image.setPath("images/" + sourceFile + "_img0.png");
        schema.getImages().add(image);
        return schema;
    }
}
