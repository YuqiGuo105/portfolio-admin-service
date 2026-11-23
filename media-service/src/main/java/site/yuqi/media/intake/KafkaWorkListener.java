package site.yuqi.media.intake;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Reads the shared work topic with the Media Service's own consumer group, so it sees every
 * message without taking any away from the search indexer.
 *
 * <p>The offset is acknowledged only after {@link WorkIntake#accept} has returned, that is, after
 * the PLAN row is committed or the message was found to be none of our business.
 */
@Component
@ConditionalOnProperty(name = "portfolio.media-pipeline.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaWorkListener {

    private final WorkIntake intake;

    public KafkaWorkListener(WorkIntake intake) {
        this.intake = intake;
    }

    @KafkaListener(
            topics = "${portfolio.media-pipeline.kafka.topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "mediaWorkListenerContainerFactory")
    public void onEvent(ConsumerRecord<String, WorkEvent> record, Acknowledgment ack) {
        intake.accept(record.value());
        ack.acknowledge();
    }
}
