package site.yuqi.admin.dto;

import lombok.Builder;
import lombok.Value;
import site.yuqi.admin.adapter.NormalizedContent;

@Value
@Builder
public class ContentCoverUploadResponse {
    String sourceType;
    String sourceId;
    String bucket;
    String objectPath;
    String imageUrl;
    String originalFileName;
    String sha256;
    String mimeType;
    long sizeBytes;
    boolean deduplicated;
    NormalizedContent content;
}
