package com.onnara.extract.db;

import com.onnara.extract.common.Errors;
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

    /** documents 1행 삽입 + 생성된 seq 반환(레코드당 1회 실행). */
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

    /** 멱등 재적재를 위한 기존 행 삭제(ref_files는 CASCADE로 함께 정리). */
    private static final String DELETE_DOCUMENTS = "DELETE FROM documents WHERE source_file = ?";

    /** ref_files 1행 삽입(이미지 1건당 1회, 배치 실행). */
    private static final String INSERT_REF_FILE = """
            INSERT INTO ref_files (document_seq, image_name, file_path) VALUES (?, ?, ?)
            """;

    /** 배치 전체에서 공유하는 커넥션(수동 커밋). */
    private final Connection conn;

    /** 커넥션을 확보하고 자동 커밋을 꺼 파일 단위 세이브포인트 격리를 준비한다. */
    public DbLoader(DataSource dataSource) throws SQLException {
        this.conn = dataSource.getConnection();
        this.conn.setAutoCommit(false);
    }

    /**
     * 파일 목록을 전부 적재한다. 파일 단위 실패는 로그 후 다음 파일로 계속 진행한다.
     *
     * <p>매핑 단계에서 적재 제외 판정을 받은 파일({@code db_skip_reason})은 건너뛴다 —
     * 판정을 여기서 존중해야 {@code pipeline} 한 번에 도는 경로와 {@code map} → {@code load}로
     * 나눠 도는 경로가 같은 결과를 낸다.
     */
    public LoadStats loadAll(List<SchemaResult> files) throws SQLException {
        int filesOk = 0;
        int filesFailed = 0;
        int filesSkipped = 0;
        int documentsInserted = 0;
        int refFilesInserted = 0;

        for (SchemaResult file : files) {
            if (file.isDbSkipped()) {
                filesSkipped++;
                System.out.println("[적재제외] " + file.getSourceFile() + ": " + file.getDbSkipReason()
                        + " — 안내문류로 보고 DB 적재를 건너뜁니다");
                continue;
            }
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
                System.out.println("[실패] " + file.getSourceFile() + ": " + Errors.describe(e));
            }
        }
        conn.commit();
        return new LoadStats(filesOk, filesFailed, filesSkipped, documentsInserted, refFilesInserted);
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

    /** 레코드 1건을 documents에 삽입하고 생성된 seq를 반환한다(파일 메타 + 15개 필드 + extras 바인딩). */
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

    /** extras 맵을 JSON 문자열로 직렬화해 jsonb 파라미터로 바인딩한다. null이면 SQL NULL. */
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

    /** 커넥션을 닫는다(try-with-resources 종료 시 호출). */
    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
