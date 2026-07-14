package com.onnara.extract.db;

import com.onnara.extract.common.Json;
import com.onnara.extract.common.model.NoticeRecord;
import com.onnara.extract.common.model.RawImage;
import com.onnara.extract.common.model.SchemaResult;
import org.postgresql.util.PGobject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;

/**
 * PostgreSQL 적재 — §6 스키마(documents/ref_files)에 표준 스키마 결과를 저장한다.
 *
 * <p>파일 단위 멱등 재적재(delete + insert, CASCADE로 ref_files 자동 정리)와
 * 세이브포인트 격리(파일 단위 실패 시 해당 파일만 롤백하고 배치는 계속)를 적용한다.
 */
public final class DbLoader implements AutoCloseable {

    private static final String INSERT_DOCUMENT = """
            INSERT INTO documents (
                source_file, file_type, is_scanned, engine,
                agency, notice_no, notice_date, title, signer,
                approval_no, approval_date, location, area,
                work_description, work_period_start, work_period_end,
                applicant_name, applicant_address, remarks, extras
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            RETURNING seq
            """;

    private static final String DELETE_DOCUMENTS = "DELETE FROM documents WHERE source_file = ?";

    private static final String INSERT_REF_FILE = """
            INSERT INTO ref_files (document_seq, image_name, file_path) VALUES (?, ?, ?)
            """;

    private final Connection conn;

    public DbLoader(DataSource dataSource) throws SQLException {
        this.conn = dataSource.getConnection();
        this.conn.setAutoCommit(false);
    }

    /** 파일 목록을 전부 적재한다. 파일 단위 실패는 로그 후 다음 파일로 계속 진행한다. */
    public LoadStats loadAll(List<SchemaResult> files) throws SQLException {
        int filesOk = 0;
        int filesFailed = 0;
        int documentsInserted = 0;
        int refFilesInserted = 0;

        for (SchemaResult file : files) {
            Savepoint savepoint = conn.setSavepoint();
            try {
                loadOne(file);
                conn.releaseSavepoint(savepoint);
                filesOk++;
                documentsInserted += file.getRecords().size();
                refFilesInserted += (int) file.getImages().stream()
                        .filter(img -> img.getPath() != null)
                        .count();
            } catch (Exception e) {
                conn.rollback(savepoint);
                filesFailed++;
                System.out.println("[실패] " + file.getSourceFile() + ": " + e.getMessage());
            }
        }
        conn.commit();
        return new LoadStats(filesOk, filesFailed, documentsInserted, refFilesInserted);
    }

    /**
     * 단일 파일을 멱등 적재한다(기존 행 삭제 후 재삽입). 대표 행(첫 레코드) seq를 반환한다.
     * 트랜잭션 커밋/롤백은 호출부({@link #loadAll})가 책임진다.
     */
    public long loadOne(SchemaResult file) throws SQLException {
        try (PreparedStatement delete = conn.prepareStatement(DELETE_DOCUMENTS)) {
            delete.setString(1, file.getSourceFile());
            delete.executeUpdate();
        }

        long representativeSeq = -1;
        try (PreparedStatement insert = conn.prepareStatement(INSERT_DOCUMENT)) {
            for (NoticeRecord record : file.getRecords()) {
                long seq = insertRecord(insert, file, record);
                if (representativeSeq < 0) {
                    representativeSeq = seq;
                }
            }
        }

        if (representativeSeq < 0) {
            return -1;
        }

        try (PreparedStatement insertRef = conn.prepareStatement(INSERT_REF_FILE)) {
            boolean hasBatch = false;
            for (RawImage image : file.getImages()) {
                if (image.getPath() == null) {
                    continue;
                }
                insertRef.setLong(1, representativeSeq);
                insertRef.setString(2, image.getName());
                insertRef.setString(3, image.getPath());
                insertRef.addBatch();
                hasBatch = true;
            }
            if (hasBatch) {
                insertRef.executeBatch();
            }
        }
        return representativeSeq;
    }

    private long insertRecord(PreparedStatement insert, SchemaResult file, NoticeRecord record)
            throws SQLException {
        int i = 1;
        insert.setString(i++, file.getSourceFile());
        insert.setString(i++, file.getFileType());
        insert.setBoolean(i++, file.isScanned());
        insert.setString(i++, file.getEngine());
        insert.setString(i++, record.agency());
        insert.setString(i++, record.noticeNo());
        setDate(insert, i++, record.noticeDate());
        insert.setString(i++, record.title());
        insert.setString(i++, record.signer());
        insert.setString(i++, record.approvalNo());
        setDate(insert, i++, record.approvalDate());
        insert.setString(i++, record.location());
        insert.setString(i++, record.area());
        insert.setString(i++, record.workDescription());
        setDate(insert, i++, record.workPeriodStart());
        setDate(insert, i++, record.workPeriodEnd());
        insert.setString(i++, record.applicantName());
        insert.setString(i++, record.applicantAddress());
        insert.setString(i++, record.remarks());
        setJsonb(insert, i, record.extras());

        try (ResultSet rs = insert.executeQuery()) {
            rs.next();
            return rs.getLong("seq");
        }
    }

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

    private static void setJsonb(PreparedStatement ps, int index, Object extras) throws SQLException {
        if (extras == null) {
            ps.setNull(index, Types.OTHER);
            return;
        }
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        try {
            jsonb.setValue(Json.MAPPER.writeValueAsString(extras));
        } catch (Exception e) {
            throw new SQLException("extras JSON 직렬화 실패", e);
        }
        ps.setObject(index, jsonb);
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
