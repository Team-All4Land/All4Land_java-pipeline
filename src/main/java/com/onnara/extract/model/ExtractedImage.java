package com.onnara.extract.model;

/** 추출된 이미지 하나를 나타내는 모델. */
public class ExtractedImage {
    private String imageId;
    private String fileHref;
    private byte[] data;
    private String caption;
    private String originalFilename;

    public ExtractedImage(String imageId, String fileHref, byte[] data) {
        this.imageId = imageId;
        this.fileHref = fileHref;
        this.data = data;
    }

    public String getImageId() { return imageId; }
    public String getFileHref() { return fileHref; }
    public byte[] getData() { return data; }
    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
}
