package com.onnara.extract;

import com.onnara.extract.model.ExtractedCell;
import com.onnara.extract.model.ExtractedDocument;
import com.onnara.extract.model.ExtractedImage;
import com.onnara.extract.model.ExtractedTable;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQLite 저장 모듈. Python db_loader.py와 동일한 스키마를 사용한다.
 * (documents / tables_extracted / images_extracted 3테이블,
 *  grid/cells는 간단한 JSON 문자열로 직접 직렬화 - 별도 JSON 라이브러리 의존성 없음)
 */
public class DbLoader {

    private static final String SCHEMA = "" +
            "CREATE TABLE IF NOT EXISTS documents (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  source_path TEXT NOT NULL," +
            "  file_type TEXT," +
            "  title TEXT," +
            "  created_date TEXT," +
            "  full_text TEXT" +
            ");" +
            "CREATE TABLE IF NOT EXISTS tables_extracted (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  document_id INTEGER NOT NULL REFERENCES documents(id)," +
            "  table_ref TEXT," +
            "  row_count INTEGER," +
            "  col_count INTEGER," +
            "  caption TEXT," +
            "  grid_json TEXT," +
            "  cells_json TEXT" +
            ");" +
            "CREATE TABLE IF NOT EXISTS images_extracted (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  document_id INTEGER NOT NULL REFERENCES documents(id)," +
            "  image_ref TEXT," +
            "  file_href TEXT," +
            "  caption TEXT," +
            "  original_filename TEXT," +
            "  file_path TEXT," +
            "  ocr_text TEXT" +
            ");";

    private final Connection conn;

    public DbLoader(String dbPath) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = conn.createStatement()) {
            for (String sql : SCHEMA.split(";")) {
                if (!sql.trim().isEmpty()) st.execute(sql);
            }
        }
        migrate();
    }

    /** 구버전 스키마로 이미 만들어진 DB 파일을 열어도 없는 컬럼을 자동으로 추가한다. */
    private void migrate() throws SQLException {
        addColumnIfMissing("documents", "file_type", "TEXT");
        addColumnIfMissing("images_extracted", "ocr_text", "TEXT");
    }

    private void addColumnIfMissing(String table, String column, String type) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (!existing.contains(column)) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            }
        }
    }

    /**
     * @param ocrTextByImageId 이미지 ID(ExtractedImage.getImageId()) -> OCR 인식 텍스트.
     *                         OCR을 안 쓰거나 아직 안 돌렸으면 null 또는 빈 맵을 넘기면 된다.
     *                         OCR 계산 자체는 이 클래스의 책임이 아니고, 호출하는 쪽(파이프라인)이
     *                         OcrProvider 등으로 미리 계산해서 넘겨준다.
     */
    public long saveDocument(ExtractedDocument doc, String imageOutputDir,
                             Map<String, String> ocrTextByImageId) throws Exception {
        Map<String, String> ocrMap = ocrTextByImageId != null ? ocrTextByImageId : Collections.emptyMap();
        new File(imageOutputDir).mkdirs();

        String fileType = fileExtension(doc.getSourcePath());

        long documentId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO documents (source_path, file_type, title, created_date, full_text) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, doc.getSourcePath());
            ps.setString(2, fileType);
            ps.setString(3, doc.getTitle());
            ps.setString(4, doc.getCreatedDate());
            ps.setString(5, doc.getFullText());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                documentId = keys.getLong(1);
            }
        }

        for (ExtractedTable table : doc.getTables()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tables_extracted (document_id, table_ref, row_count, col_count, caption, grid_json, cells_json) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, documentId);
                ps.setString(2, table.getTableId());
                ps.setInt(3, table.getRowCount());
                ps.setInt(4, table.getColCount());
                ps.setString(5, table.getCaption());
                ps.setString(6, gridToJson(table.getGrid()));
                ps.setString(7, cellsToJson(table.getCells()));
                ps.executeUpdate();
            }
        }

        int i = 0;
        for (ExtractedImage image : doc.getImages()) {
            String ext = guessExt(image);
            String safeName = "doc" + documentId + "_" + (i++) + ext;
            File outPath = Paths.get(imageOutputDir, safeName).toFile();
            try (FileOutputStream fos = new FileOutputStream(outPath)) {
                fos.write(image.getData());
            }

            String ocrText = ocrMap.get(image.getImageId());
            if (ocrText != null && ocrText.isEmpty()) ocrText = null;

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO images_extracted (document_id, image_ref, file_href, caption, original_filename, file_path, ocr_text) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, documentId);
                ps.setString(2, image.getImageId());
                ps.setString(3, image.getFileHref());
                ps.setString(4, image.getCaption());
                ps.setString(5, image.getOriginalFilename());
                ps.setString(6, outPath.getPath());
                ps.setString(7, ocrText);
                ps.executeUpdate();
            }
        }

        return documentId;
    }

    public void close() throws SQLException {
        conn.close();
    }

    private String fileExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1).toLowerCase() : "";
    }

    /** 매직바이트로 실제 이미지 포맷을 판별해 올바른 확장자를 붙인다. */
    private String guessExt(ExtractedImage image) {
        byte[] d = image.getData();
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return ".jpg";
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return ".png";
        if (d.length >= 2 && d[0] == 'B' && d[1] == 'M') return ".bmp";
        if (d.length >= 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') return ".gif";
        return ".bin";
    }

    // -- 아주 단순한 JSON 직렬화 (별도 라이브러리 의존성 없이, 문자열 이스케이프만 처리) --
    private String gridToJson(List<List<String>> grid) {
        StringBuilder sb = new StringBuilder("[");
        for (int r = 0; r < grid.size(); r++) {
            if (r > 0) sb.append(",");
            sb.append("[");
            List<String> row = grid.get(r);
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append(",");
                sb.append(jsonString(row.get(c)));
            }
            sb.append("]");
        }
        sb.append("]");
        return sb.toString();
    }

    private String cellsToJson(List<ExtractedCell> cells) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(",");
            ExtractedCell c = cells.get(i);
            sb.append("{")
                    .append("\"row\":").append(c.getRow()).append(",")
                    .append("\"col\":").append(c.getCol()).append(",")
                    .append("\"row_span\":").append(c.getRowSpan()).append(",")
                    .append("\"col_span\":").append(c.getColSpan()).append(",")
                    .append("\"text\":").append(jsonString(c.getText()))
                    .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}