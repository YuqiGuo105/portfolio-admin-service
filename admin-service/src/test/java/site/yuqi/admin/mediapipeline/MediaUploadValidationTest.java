package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaUploadValidationTest {
    private final UUID id = UUID.randomUUID();
    private final MediaAssetStore assets = mock(MediaAssetStore.class);
    private final OriginalsStore storage = mock(OriginalsStore.class);
    private MediaUploadService service;

    @BeforeEach
    void setUp() {
        var properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("portfolio.media-pipeline", MediaPipelineProperties.class);
        service = new MediaUploadService(assets, storage, properties, mock(MediaOutboxRelay.class),
                mock(PlatformTransactionManager.class), new ObjectMapper());
    }

    @Test
    void expiredSessionsCannotResumeOrCompleteBeforeTheJanitorRuns() {
        when(assets.find(id)).thenReturn(Optional.of(asset(Instant.now().minusSeconds(1))));
        assertThatThrownBy(() -> service.resume(id, "upload")).hasMessageContaining("expired");
        assertThatThrownBy(() -> service.complete(id, "upload")).hasMessageContaining("expired");
        verifyNoInteractions(storage);
    }

    @Test
    void databaseFailureAbortsTheUnboundMultipartUpload() throws Exception {
        when(storage.createMultipartUpload(anyString(), anyString())).thenReturn("store-upload");
        doThrow(new IllegalStateException("database down")).when(assets).insertUploading(any(), anyString(), any(),
                anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyLong(), anyInt(), anyInt(), anyString());
        assertThatThrownBy(() -> service.start(new MediaUploadService.StartUpload(null, "clip.mp4", "video/mp4", 10, null), "admin"))
                .hasMessageContaining("database down");
        verify(storage).abortMultipartUpload(anyString(), eq("store-upload"));
    }

    @Test
    void malformedPartNumbersCannotBeJoinedEvenWhenTotalSizeMatches() throws Exception {
        when(assets.find(id)).thenReturn(Optional.of(asset(Instant.now().plusSeconds(60))));
        when(storage.listParts("source", "store-upload")).thenReturn(List.of(
                new OriginalsStore.Part(2, "a", 5), new OriginalsStore.Part(3, "b", 5)));
        assertThatThrownBy(() -> service.complete(id, "upload")).hasMessageContaining("invalid part");
        verify(storage, never()).completeMultipartUpload(anyString(), anyString(), anyList());
    }

    @Test
    void longTitlesAreRejectedBeforeStorageAllocation() {
        assertThatThrownBy(() -> service.start(new MediaUploadService.StartUpload(null, "clip.mp4", "video/mp4", 10, "x".repeat(301)), "admin"))
                .hasMessageContaining("300");
        verifyNoInteractions(storage, assets);
    }

    private MediaAssetStore.AssetRow asset(Instant expiry) {
        return new MediaAssetStore.AssetRow(id, "VIDEO", "UPLOADING", null, "clip.mp4", "video/mp4", 10,
                "upload", "store-upload", "source", 5L, 2, expiry, 0, null, "v1", null, null, null,
                Instant.now(), Instant.now(), null);
    }
}
