package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MediaOutboxRelayTest {
    @Test void aRecoveryRequestDoesNotDrainAnUnboundedBacklog() throws Exception {
        var store=mock(MediaAssetStore.class);
        var transport=mock(MediaEventTransport.class);
        when(store.claimOutbox(1,120)).thenAnswer(call -> List.of(new MediaAssetStore.OutboxRow(
                UUID.randomUUID(),UUID.randomUUID().toString(),UUID.randomUUID(),1,"test","{}",1)));
        var config=new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("portfolio.media-pipeline",MediaPipelineProperties.class);
        var relay=new MediaOutboxRelay(store,transport,new ObjectMapper(),config);
        try {
            assertThat(relay.drainOnce()).isEqualTo(20);
            verify(transport,times(20)).send(any());
            verify(store,times(20)).claimOutbox(1,120);
        } finally { relay.close(); }
    }
}
