package site.yuqi.admin.media;

import org.junit.jupiter.api.Test;
import site.yuqi.admin.dto.ContentCoverUploadRequest;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoverImageLoaderTest {

    private final CoverImageLoader loader = new CoverImageLoader(1024, 1, "images.example.com");

    @Test
    void acceptsBase64AndDetectsMimeFromSignature() {
        ContentCoverUploadRequest request = requestWithBase64(pngBytes(), "image/png");

        CoverImageLoader.LoadedCover result = loader.load(request);

        assertEquals("image/png", result.mimeType());
        assertEquals("png", result.extension());
    }

    @Test
    void rejectsClaimedMimeThatDoesNotMatchSignature() {
        ContentCoverUploadRequest request = requestWithBase64(pngBytes(), "image/jpeg");

        assertThrows(IllegalArgumentException.class, () -> loader.load(request));
    }

    @Test
    void requiresExactlyOneInputSource() {
        ContentCoverUploadRequest request = requestWithBase64(pngBytes(), null);
        request.setSourceUrl("https://images.example.com/cover.png");

        assertThrows(IllegalArgumentException.class, () -> loader.load(request));
    }

    @Test
    void rejectsRemoteHostOutsideConfiguredAllowlistBeforeDownload() {
        ContentCoverUploadRequest request = new ContentCoverUploadRequest();
        request.setSourceUrl("https://untrusted.example.net/cover.png");

        assertThrows(IllegalArgumentException.class, () -> loader.load(request));
    }

    private static ContentCoverUploadRequest requestWithBase64(byte[] bytes, String mimeType) {
        ContentCoverUploadRequest request = new ContentCoverUploadRequest();
        request.setImageBase64(Base64.getEncoder().encodeToString(bytes));
        request.setMimeType(mimeType);
        return request;
    }

    private static byte[] pngBytes() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0x00};
    }
}
