package site.yuqi.admin.dto;

import lombok.Data;

@Data
public class ContentCoverUploadRequest {
    /** HTTPS image URL fetched by admin-service. Mutually exclusive with imageBase64. */
    private String sourceUrl;
    /** Raw base64 or a data:image/...;base64 URI. Mutually exclusive with sourceUrl. */
    private String imageBase64;
    /** Original filename used only for audit-friendly response metadata. */
    private String fileName;
    /** Optional claimed MIME type. The service verifies it against file signatures. */
    private String mimeType;
    private String changeNote;
}
