package site.yuqi.admin.mediapipeline;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Publishes to the existing work topic. No new topic: media events share
 * {@code content.search.index.v1} with indexing events and are told apart by
 * {@code jobType = MEDIA_PROCESS}. The Media Service reads the topic with its own consumer group.
 */
@Component
@ConditionalOnExpression("'${portfolio.media-pipeline.enabled:false}' == 'true' "
        + "and '${portfolio.media-pipeline.events.transport:kafka}' == 'kafka'")
public class KafkaMediaEventTransport implements MediaEventTransport {

    private final KafkaTemplate<String, Object> kafka;
    private final String topic;

    public KafkaMediaEventTransport(KafkaTemplate<String, Object> kafka, MediaPipelineProperties properties) {
        this.kafka = kafka;
        this.topic = properties.events().topic();
    }

    @Override
    public void send(MediaWorkEvent event) throws Exception {
        // Waits for the broker's acknowledgement (acks=all): only then may the outbox row be marked sent.
        kafka.send(topic, event.partitionKey(), event).get(30, TimeUnit.SECONDS);
    }
}
