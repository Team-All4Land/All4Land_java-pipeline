package com.onnara.extract.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * hwp/hwpx/hml 세 파서가 공통으로 반환하는 문서 모델.
 * Python 버전의 ExtractedDocument와 동일한 스키마를 유지한다.
 */
public class ExtractedDocument {
    private String sourcePath;
    private String title;
    private String originalTitle; // 파일 메타데이터에 있던 원래 제목 (참고용)
    private String createdDate;
    private List<String> paragraphs = new ArrayList<>();
    private List<ExtractedTable> tables = new ArrayList<>();
    private List<ExtractedImage> images = new ArrayList<>();

    public ExtractedDocument(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public String getSourcePath() { return sourcePath; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getOriginalTitle() { return originalTitle; }
    public void setOriginalTitle(String originalTitle) { this.originalTitle = originalTitle; }
    public String getCreatedDate() { return createdDate; }
    public void setCreatedDate(String createdDate) { this.createdDate = createdDate; }
    public List<String> getParagraphs() { return paragraphs; }
    public List<ExtractedTable> getTables() { return tables; }
    public List<ExtractedImage> getImages() { return images; }

    /**
     * 문서 전체 텍스트. 표 밖 문단 + 표 안 셀 텍스트(중복 제거)를 모두 포함한다.
     * (표의 정확한 구조 자체가 필요하면 getTables()의 grid를 쓰면 됨)
     */
    public String getFullText() {
        List<String> parts = new ArrayList<>(paragraphs);
        Set<String> seen = new LinkedHashSet<>();
        for (ExtractedTable table : tables) {
            for (List<String> row : table.getGrid()) {
                for (String cellText : row) {
                    if (cellText != null && !cellText.isEmpty() && seen.add(cellText)) {
                        parts.add(cellText);
                    }
                }
            }
        }
        return String.join("\n", parts);
    }
}
