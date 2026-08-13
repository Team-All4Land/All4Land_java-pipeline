package com.onnara.extract.db;

import com.onnara.extract.common.Errors;
import com.onnara.extract.common.SourceFileName;
import com.onnara.extract.common.Synonyms;
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
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 적재 — 공고 → 첨부 → 항목값 3계층으로 저장한다.
 *
 * <p>멱등 단위는 <b>첨부파일</b>이다({@code (notice_no, attach_no)}). 게시물 단위로 지우면
 * 파일을 한 건씩 처리하는 도중 같은 게시물의 앞선 첨부가 함께 날아간다. 첨부 행을 지우면
 * CASCADE로 이미지와 항목값이 함께 정리되므로 재적재가 안전하다.
 *
 * <p>성공한 파일만 넣지 않는다 — 실패·적재제외도 {@code status_code}를 달아 행으로 남긴다.
 * 성공만 적재하면 "첨부 401건 중 추출 0건"인 기관이 DB에서 아예 보이지 않는다.
 */
public final class DbLoader implements AutoCloseable {

    /** 게시물 행 — 같은 게시물의 첨부가 여럿이므로 최초 1회만 만든다. */
    private static final String INSERT_NOTICE = """
            INSERT INTO notices (notice_no) VALUES (?) ON CONFLICT (notice_no) DO NOTHING
            """;

    /** 멱등 재적재를 위한 기존 첨부 삭제(이미지·항목값은 CASCADE로 함께 정리). */
    private static final String DELETE_ATTACHMENT =
            "DELETE FROM attachments WHERE notice_no = ? AND attach_no = ?";

    /** 첨부 1행 — 파일 메타 + 문서 단위 메타 + 추출 상태. */
    private static final String INSERT_ATTACHMENT = """
            INSERT INTO attachments (
                notice_no, attach_no, file_name, status_code,
                fail_stage, fail_kind, fail_message, skip_reason,
                ext_outer, ext_inner, is_scanned, engine,
                agency_name, doc_no, doc_date, title, signer, record_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** 이미지 1행(첨부당 N건, 배치 실행). */
    private static final String INSERT_IMAGE = """
            INSERT INTO attachment_images (notice_no, attach_no, image_no, caption, abs_path)
            VALUES (?, ?, ?, ?, ?)
            """;

    /** 항목값 1행(레코드당 N건, 배치 실행). */
    private static final String INSERT_ATTRIBUTE = """
            INSERT INTO document_attributes (notice_no, attach_no, record_no, attr_code, seq, value)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    /** 적재 상태 — 추출 성공. */
    private static final String STATUS_OK = "ok";

    /** 적재 상태 — 판별·추출·매핑 실패. */
    private static final String STATUS_FAILED = "failed";

    /** 적재 상태 — 안내문류로 보고 항목 적재를 건너뜀. */
    private static final String STATUS_SKIPPED = "skipped";

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
     * @param stage    실패한 단계(판별 / 추출 / 표해석 / 매핑)
     * @param kind     실패 갈래({@link com.onnara.extract.detect.FailureKind})
     * @param message  원인 체인까지 담은 사유 문장
     */
    public record FailedAttachment(String fileName, String stage, String kind, String message) {
    }

    /**
     * 성공·실패 첨부를 모두 적재한다. 첨부 단위 실패는 로그 후 다음 건으로 계속 진행한다.
     *
     * <p>매핑 단계에서 적재 제외 판정을 받은 파일({@code db_skip_reason})도 첨부 행은 남긴다 —
     * 판정 사실이 DB에 있어야 "왜 이 파일의 값이 없는지"를 나중에 설명할 수 있다.
     */
    public LoadStats loadAll(List<SchemaResult> files, List<FailedAttachment> failures)
            throws SQLException {
        int filesOk = 0;
        int filesFailed = 0;
        int filesSkipped = 0;
        int recordsInserted = 0;
        int imagesInserted = 0;

        for (SchemaResult file : files) {
            Savepoint savepoint = conn.setSavepoint();
            try {
                Counts counts = loadOne(file);
                conn.releaseSavepoint(savepoint);
                if (file.isDbSkipped()) {
                    filesSkipped++;
                    System.out.println("[적재제외] " + file.getSourceFile() + ": "
                            + file.getDbSkipReason() + " — 안내문류로 보고 항목 적재를 건너뜁니다");
                } else {
                    filesOk++;
                }
                recordsInserted += counts.records();
                imagesInserted += counts.images();
            } catch (Exception e) {
                conn.rollback(savepoint);
                filesFailed++;
                System.out.println("[실패] " + file.getSourceFile() + ": " + Errors.describe(e));
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
        return new LoadStats(filesOk, filesFailed, filesSkipped, recordsInserted, imagesInserted);
    }

    /**
     * 첨부 1건을 멱등 적재한다(기존 행 삭제 후 재삽입).
     * 트랜잭션 커밋/롤백은 호출부({@link #loadAll})가 책임진다.
     *
     * @return 삽입한 (항목값 행 수, 이미지 행 수)
     */
    public Counts loadOne(SchemaResult file) throws SQLException {
        SourceFileName.Parsed name = file.attachmentKey();
        prepareSlot(name);

        List<NoticeRecord> records = file.getRecords();
        // 적재제외 파일은 첨부 메타만 남기고 항목값은 넣지 않는다
        boolean loadValues = !file.isDbSkipped();
        NoticeRecord meta = records.isEmpty() ? null : records.get(0);

        insertAttachment(file, name, meta, loadValues ? records.size() : 0);

        int attributes = 0;
        if (loadValues) {
            attributes = insertAttributes(name, records);
        }
        int images = insertImages(file, name);
        return new Counts(attributes, images);
    }

    /** 판별·추출 실패 건을 첨부 행으로만 남긴다(항목값 없음). */
    private void loadFailure(FailedAttachment failure) throws SQLException {
        SourceFileName.Parsed name = SourceFileName.parse(failure.fileName());
        prepareSlot(name);

        try (PreparedStatement ps = conn.prepareStatement(INSERT_ATTACHMENT)) {
            ps.setInt(1, name.noticeNo());
            ps.setInt(2, name.attachNo());
            ps.setString(3, failure.fileName());
            ps.setString(4, STATUS_FAILED);
            ps.setString(5, failure.stage());
            ps.setString(6, failure.kind());
            ps.setString(7, failure.message());
            ps.setNull(8, Types.VARCHAR);        // skip_reason
            ps.setString(9, extensionOf(failure.fileName()));
            ps.setNull(10, Types.VARCHAR);       // ext_inner — 판별에 실패했으면 알 수 없다
            ps.setBoolean(11, false);            // is_scanned — 위와 같은 이유로 단정하지 않는다
            ps.setNull(12, Types.VARCHAR);       // engine
            ps.setNull(13, Types.VARCHAR);       // agency_name
            ps.setNull(14, Types.VARCHAR);       // doc_no
            ps.setNull(15, Types.DATE);          // doc_date
            ps.setNull(16, Types.VARCHAR);       // title
            ps.setNull(17, Types.VARCHAR);       // signer
            ps.setInt(18, 0);
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

    /** 게시물 행을 확보하고 같은 첨부의 이전 적재분을 지운다. */
    private void prepareSlot(SourceFileName.Parsed name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_NOTICE)) {
            ps.setInt(1, name.noticeNo());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(DELETE_ATTACHMENT)) {
            ps.setInt(1, name.noticeNo());
            ps.setInt(2, name.attachNo());
            ps.executeUpdate();
        }
    }

    /** 첨부 1행 삽입 — 파일 메타는 SchemaResult에서, 문서 메타는 첫 레코드에서 가져온다. */
    private void insertAttachment(SchemaResult file, SourceFileName.Parsed name,
                                  NoticeRecord meta, int recordCount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_ATTACHMENT)) {
            int i = 1;
            ps.setInt(i++, name.noticeNo());
            ps.setInt(i++, name.attachNo());
            ps.setString(i++, file.getSourceFile());
            ps.setString(i++, file.isDbSkipped() ? STATUS_SKIPPED : STATUS_OK);
            ps.setNull(i++, Types.VARCHAR);                    // fail_stage
            ps.setNull(i++, Types.VARCHAR);                    // fail_kind
            ps.setNull(i++, Types.VARCHAR);                    // fail_message
            ps.setString(i++, file.getDbSkipReason());
            ps.setString(i++, file.getFileType());
            ps.setString(i++, file.getDetectedFormat());
            ps.setBoolean(i++, file.isScanned());
            ps.setString(i++, file.getEngine());
            ps.setString(i++, meta == null ? null : meta.agency());
            ps.setString(i++, meta == null ? null : meta.noticeNo());
            setDate(ps, i++, meta == null ? null : meta.noticeDate());
            ps.setString(i++, meta == null ? null : meta.title());
            ps.setString(i++, meta == null ? null : meta.signer());
            ps.setInt(i, recordCount);
            ps.executeUpdate();
        }
    }

    /**
     * 레코드마다 {@code record_no}를 1부터 부여하고 표준항목 값을 행으로 펼친다.
     *
     * <p>문서 단위 메타(기관·고시번호·고시일자·제목·고시자)는 첨부 컬럼으로 이미 갔으므로
     * 건너뛴다. 기간은 시작/종료를 daterange 리터럴 한 행으로 합친다.
     */
    private int insertAttributes(SourceFileName.Parsed name, List<NoticeRecord> records)
            throws SQLException {
        int inserted = 0;
        try (PreparedStatement ps = conn.prepareStatement(INSERT_ATTRIBUTE)) {
            int recordNo = 0;
            for (NoticeRecord record : records) {
                recordNo++;
                for (Map.Entry<String, String> entry : record.fields().entrySet()) {
                    String code = entry.getKey();
                    if (isAttachmentScoped(code) || isPeriodPart(code)) {
                        continue;
                    }
                    addAttribute(ps, name, recordNo, code, entry.getValue());
                    inserted++;
                }
                String period = periodRange(record);
                if (period != null) {
                    addAttribute(ps, name, recordNo, Synonyms.WORK_PERIOD, period);
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
        ps.setInt(1, name.noticeNo());
        ps.setInt(2, name.attachNo());
        ps.setInt(3, recordNo);
        ps.setString(4, attrCode);
        ps.setInt(5, 1);
        ps.setString(6, value);
        ps.addBatch();
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
                ps.setInt(1, name.noticeNo());
                ps.setInt(2, name.attachNo());
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

    /**
     * 기간 시작/종료를 PostgreSQL daterange 리터럴로 합친다.
     *
     * <p>둘 중 하나라도 없으면 null — 반쪽짜리 범위를 넣느니 항목을 비우고, 원문은
     * Mapper가 이미 extras에 남겨 뒀다. 양끝을 포함하는 닫힌 구간으로 적는다(점용 기간의
     * 종료일은 그날까지 쓸 수 있다는 뜻이다).
     */
    private static String periodRange(NoticeRecord record) {
        String start = record.workPeriodStart();
        String end = record.workPeriodEnd();
        return start == null || end == null ? null : "[" + start + "," + end + "]";
    }

    /** 문서 단위 메타인지 — attachments 컬럼으로 이미 갔으므로 항목값으로 또 넣지 않는다. */
    private static boolean isAttachmentScoped(String canonical) {
        return Synonyms.field(canonical).map(f -> !f.isAttribute()).orElse(false);
    }

    /** 기간 분리 파생 필드인지 — daterange 한 행으로 합쳐 넣으므로 개별로는 넣지 않는다. */
    private static boolean isPeriodPart(String canonical) {
        return Synonyms.WORK_PERIOD_START.equals(canonical)
                || Synonyms.WORK_PERIOD_END.equals(canonical);
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
     * @param records document_attributes에 넣은 항목값 행 수
     * @param images  attachment_images에 넣은 이미지 행 수
     */
    public record Counts(int records, int images) {
    }

    /** 커넥션을 닫는다(try-with-resources 종료 시 호출). */
    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
