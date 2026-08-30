package site.yuqi.admin.media;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.dto.ContentCoverUploadRequest;
import site.yuqi.admin.dto.ContentCoverUploadResponse;
import site.yuqi.admin.service.ContentService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentCoverServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void uploadsImmutableObjectAndUpdatesProjectCover() {
        ContentService contentService = mock(ContentService.class);
        CoverImageLoader loader = mock(CoverImageLoader.class);
        ContentCoverStorage storage = mock(ContentCoverStorage.class);
        ContentCoverUploadRequest request = new ContentCoverUploadRequest();
        request.setFileName("folder/cover.png");
        NormalizedContent content = mock(NormalizedContent.class);
        when(contentService.getOrThrow(SourceType.PROJECT, "project-1")).thenReturn(content);
        when(loader.load(request)).thenReturn(
                new CoverImageLoader.LoadedCover(new byte[] {1, 2, 3}, "image/png", "png"));
        when(storage.put(anyString(), any(byte[].class), anyString())).thenReturn(
                new ContentCoverStorage.StoredCover(
                        "project/project-1/hash.png", "https://cdn.example/cover.png", true));
        when(storage.bucket()).thenReturn("project-covers");
        when(contentService.update(any(), anyString(), any(), anyBoolean(), anyString(), anyString()))
                .thenReturn(content);

        ContentCoverUploadResponse result = new ContentCoverService(contentService, loader, storage)
                .upload(SourceType.PROJECT, "project-1", request, "admin@example.com");

        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(contentService).update(
                org.mockito.ArgumentMatchers.eq(SourceType.PROJECT),
                org.mockito.ArgumentMatchers.eq("project-1"), patch.capture(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("admin@example.com"), anyString());
        assertEquals("https://cdn.example/cover.png", patch.getValue().get("imageUrl"));
        assertEquals("IMAGE", patch.getValue().get("coverVariant"));
        assertEquals("project-covers", result.getBucket());
        assertEquals("cover.png", result.getOriginalFileName());
    }

    @Test
    void compensatesNewStorageObjectWhenDatabaseUpdateFails() {
        ContentService contentService = mock(ContentService.class);
        CoverImageLoader loader = mock(CoverImageLoader.class);
        ContentCoverStorage storage = mock(ContentCoverStorage.class);
        ContentCoverUploadRequest request = new ContentCoverUploadRequest();
        when(contentService.getOrThrow(SourceType.BLOG, "blog-1")).thenReturn(mock(NormalizedContent.class));
        when(loader.load(request)).thenReturn(
                new CoverImageLoader.LoadedCover(new byte[] {1, 2, 3}, "image/png", "png"));
        when(storage.put(anyString(), any(byte[].class), anyString())).thenReturn(
                new ContentCoverStorage.StoredCover("blog/blog-1/hash.png", "https://cdn.example/cover.png", true));
        when(contentService.update(any(), anyString(), any(), anyBoolean(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));

        ContentCoverService service = new ContentCoverService(contentService, loader, storage);

        assertThrows(IllegalStateException.class,
                () -> service.upload(SourceType.BLOG, "blog-1", request, "admin@example.com"));
        verify(storage).delete("blog/blog-1/hash.png");
    }

    @Test
    void doesNotDeletePreviouslyExistingObjectWhenDatabaseUpdateFails() {
        ContentService contentService = mock(ContentService.class);
        CoverImageLoader loader = mock(CoverImageLoader.class);
        ContentCoverStorage storage = mock(ContentCoverStorage.class);
        ContentCoverUploadRequest request = new ContentCoverUploadRequest();
        when(contentService.getOrThrow(SourceType.BLOG, "blog-1")).thenReturn(mock(NormalizedContent.class));
        when(loader.load(request)).thenReturn(
                new CoverImageLoader.LoadedCover(new byte[] {1, 2, 3}, "image/png", "png"));
        when(storage.put(anyString(), any(byte[].class), anyString())).thenReturn(
                new ContentCoverStorage.StoredCover("blog/blog-1/hash.png", "https://cdn.example/cover.png", false));
        when(contentService.update(any(), anyString(), any(), anyBoolean(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));

        ContentCoverService service = new ContentCoverService(contentService, loader, storage);

        assertThrows(IllegalStateException.class,
                () -> service.upload(SourceType.BLOG, "blog-1", request, "admin@example.com"));
        verify(storage, never()).delete(anyString());
    }
}
