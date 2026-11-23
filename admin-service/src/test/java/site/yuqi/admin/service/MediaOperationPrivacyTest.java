package site.yuqi.admin.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MediaOperationPrivacyTest {
    @Test void persistsReceiptButNeverSignedUploadUrls() {
        var raw = Map.<String, Object>of("assetId", "asset", "uploadId", "upload",
                "parts", List.of(Map.of("url", "https://storage.example/?X-Goog-Signature=secret")),
                "unexpectedCredential", "secret");
        for (String tool : List.of("media.start_upload", "media.resume_upload")) {
            var stored = McpOperationService.storedResponse(tool, raw);
            assertThat(stored).containsEntry("assetId", "asset").containsEntry("signedUrlsOmitted", true);
            assertThat(stored.toString()).doesNotContain("secret", "Signature", "storage.example");
            assertThat(raw).containsKey("parts");
        }
        assertThat(McpOperationService.storedResponse("media.update", Map.of("metadataVersion", 2)))
                .containsEntry("metadataVersion", 2);
    }

    @Test void failedUploadDoesNotInstructClientToResumeANonexistentSession() {
        var stored = McpOperationService.storedResponse("media.start_upload", Map.of(
                "code", "downstream_error", "retryable", false, "ambiguousOutcome", true,
                "message", "Do not retain https://storage.example/?signature=private"));
        assertThat(stored).containsEntry("code", "downstream_error").containsEntry("retryable", false)
                .containsEntry("ambiguousOutcome", true).doesNotContainKey("message");
        assertThat(stored.get("nextAction").toString()).contains("Inspect operation status");
    }
}
