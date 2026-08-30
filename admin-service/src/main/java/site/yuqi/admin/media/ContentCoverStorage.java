package site.yuqi.admin.media;

public interface ContentCoverStorage {
    StoredCover put(String objectPath, byte[] bytes, String mimeType);
    void delete(String objectPath);
    String bucket();

    record StoredCover(String objectPath, String publicUrl, boolean created) {}
}
