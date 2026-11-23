package site.yuqi.admin.mediapipeline;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named=MediaTestSchema.ENV,matches=MediaTestSchema.LOCAL_ONLY)
class MediaWakeRecoveryTest {
    private MediaTestSchema db;
    private MediaWakeStore store;
    private UUID asset;
    private UUID event;
    @BeforeEach void setup() {
        db=new MediaTestSchema(); store=new MediaWakeStore(db.jdbc);
        asset=UUID.randomUUID(); event=UUID.randomUUID();
        db.jdbc.update("""
                insert into media_assets(id,kind,status,original_filename,content_type,size_bytes,source_version,created_by)
                values(?,'VIDEO','PROCESSING','test.mp4','video/mp4',100,1,'test')
                """,asset);
        db.jdbc.update("""
                insert into media_event_outbox(id,event_id,event_type,asset_id,source_version,partition_key,payload,status)
                values(?,?,'MEDIA_PROCESS',?,1,'test','{}','SENT')
                """,event,event.toString(),asset);
    }
    @AfterEach void cleanup() { db.close(); }

    @Test void aFailedWakeIsRetriedAfterBackoffAndSurvivesDispatcherRestart() {
        var first=store.claim(1,60,20).getFirst();
        assertThat(store.finish(first,false,20)).isTrue();
        assertThat(store.claim(1,60,20)).isEmpty();
        due();
        var restarted=new MediaWakeStore(db.jdbc);
        var next=restarted.claim(1,60,20).getFirst();
        assertThat(next.attempt()).isEqualTo(2);
        assertThat(restarted.finish(next,true,20)).isTrue();
        assertThat(restarted.counts()).containsEntry("DONE",1);
    }

    @Test void expiredLeaseCanBeReclaimedButTheOldOwnerCannotOverwriteIt() {
        var old=store.claim(1,60,20).getFirst();
        assertThat(store.claim(1,60,20)).isEmpty();
        db.jdbc.update("update media_event_outbox set wake_lease_until=now()-interval '1 second'");
        var replacement=store.claim(1,60,20).getFirst();
        assertThat(store.finish(old,true,20)).isFalse();
        assertThat(store.finish(replacement,true,20)).isTrue();
    }

    @Test void boundedRetriesParkWakeWithoutResendingKafkaOrDeletingWork() {
        var delivery=store.claim(1,60,20).getFirst();
        store.finish(delivery,false,1); due();
        assertThat(store.claim(1,60,20)).isEmpty();
        assertThat(store.counts()).containsEntry("DLQ",1);
        assertThat(db.text("select status from media_event_outbox")).isEqualTo("SENT");
        assertThat(db.text("select status from media_assets")).isEqualTo("PROCESSING");
    }

    @Test void aCrashOnTheLastAttemptCannotCauseUnboundedWakeRequests() {
        store.claim(1,60,1);
        db.jdbc.update("update media_event_outbox set wake_lease_until=now()-interval '1 second'");
        assertThat(store.claim(1,60,1)).isEmpty();
        assertThat(store.counts()).containsEntry("DLQ",1);
    }

    @Test void unfinishedHttpResponseIsNotMarkedDone() {
        var wake=mock(MediaServiceWakeClient.class);
        when(wake.isConfigured()).thenReturn(true); when(wake.waitMillis()).thenReturn(1000L);
        when(wake.wakeAndAwait(asset)).thenReturn(false,true);
        var config=new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("portfolio.media-pipeline",MediaPipelineProperties.class);
        try(var ignored=new AutoCloseableDispatcher(new MediaWakeDispatcher(store,wake,config))) {
            assertThat(ignored.dispatcher.drainAndAwait()).containsEntry("PENDING",1);
            due();
            assertThat(ignored.dispatcher.drainAndAwait()).containsEntry("DONE",1);
            verify(wake,times(2)).wakeAndAwait(asset);
        }
    }

    @Test void terminalAssetsDoNotConsumeMoreWakeRequests() {
        db.jdbc.update("update media_assets set status='FAILED'");
        assertThat(store.claim(2,60,20)).isEmpty();
        assertThat(store.counts()).containsEntry("DONE",1);
    }

    @Test void recoveryEndpointRequiresItsOwnTokenBeforeAnySideEffect() {
        var relay=mock(MediaOutboxRelay.class); var wakes=mock(MediaWakeDispatcher.class);
        String secret="local-test-recovery-token-0123456789";
        var controller=new MediaRecoveryController(relay,wakes,secret);
        assertThat(controller.recover(null).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.recover("wrong").getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(relay,wakes);
        when(wakes.drainAndAwait()).thenReturn(Map.of("DONE",1));
        assertThat(controller.recover(secret).getStatusCode().value()).isEqualTo(200);
        verify(relay).drainOnce(); verify(wakes).drainAndAwait();
        assertThat(new MediaRecoveryController(relay,wakes,"").recover("").getStatusCode().value()).isEqualTo(401);
    }

    private void due() { db.jdbc.update("update media_event_outbox set wake_after=now()-interval '1 second'"); }
    private record AutoCloseableDispatcher(MediaWakeDispatcher dispatcher) implements AutoCloseable {
        public void close() { dispatcher.close(); }
    }
}
