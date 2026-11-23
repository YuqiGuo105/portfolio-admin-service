package site.yuqi.media.intake;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The topic is shared, so the wire format is a contract. These tests put bytes through the same
 * Spring Kafka JSON classes the producer (Content Service) and the consumers use.
 */
class WorkEventJsonTest {

    @Test
    void readsTheMediaEventTheContentServiceWrites() {
        Map<String, Object> written = new LinkedHashMap<>();
        written.put("eventId", "evt-001");
        written.put("occurredAt", Instant.parse("2026-11-22T10:15:30.250Z"));
        written.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        written.put("correlationId", "corr-1");
        written.put("schemaVersion", 1);
        written.put("sourceType", "MEDIA");
        written.put("sourceId", "0b0e8d0c-9f2b-4a55-9d53-2f0c6c1f2b11");
        written.put("sourceVersion", 1);
        written.put("idempotencyKey", "media:0b0e8d0c-9f2b-4a55-9d53-2f0c6c1f2b11:1:v1");
        written.put("jobType", "MEDIA_PROCESS");
        written.put("sourceKey", "originals/0b0e8d0c-9f2b-4a55-9d53-2f0c6c1f2b11/u1/demo.mp4");
        written.put("profileVersion", "v1");

        WorkEvent event = roundTrip(written);

        assertThat(event.isMediaWork()).isTrue();
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-11-22T10:15:30.250Z"));
        assertThat(event.sourceVersion()).isEqualTo(1);
        assertThat(event.sourceKey()).endsWith("/demo.mp4");
        assertThat(event.indexingJobId()).isNull();
    }

    @Test
    void readsPastTheIndexingEventsThatShareTheTopic() {
        Map<String, Object> indexing = new LinkedHashMap<>();
        indexing.put("eventId", "evt-002");
        indexing.put("occurredAt", Instant.now());
        indexing.put("causationId", "a1b2");
        indexing.put("schemaVersion", 1);
        indexing.put("sourceType", "BLOG");
        indexing.put("sourceId", "42");
        indexing.put("sourceVersion", 7);
        indexing.put("indexingJobId", "5d3f3b0e-7a37-4a69-8d5c-3a4a2f1f6c10");
        indexing.put("idempotencyKey", "BLOG:42:7:SEARCH_INDEX");
        indexing.put("jobType", "SEARCH_INDEX");
        indexing.put("somethingAddedLater", "ignored");

        WorkEvent event = roundTrip(indexing);

        assertThat(event.isMediaWork()).isFalse();
        assertThat(event.jobType()).isEqualTo("SEARCH_INDEX");
    }

    /** Serialize like the Content Service's KafkaTemplate, deserialize like this service's listener. */
    private static WorkEvent roundTrip(Map<String, Object> value) {
        try (JsonSerializer<Object> serializer = new JsonSerializer<>();
             JsonDeserializer<WorkEvent> deserializer = new JsonDeserializer<>()) {
            deserializer.configure(Map.of(
                    JsonDeserializer.VALUE_DEFAULT_TYPE, WorkEvent.class.getName(),
                    JsonDeserializer.USE_TYPE_INFO_HEADERS, false,
                    JsonDeserializer.TRUSTED_PACKAGES, WorkEvent.class.getPackageName()), false);
            return deserializer.deserialize("content.search.index.v1", serializer.serialize("content.search.index.v1", value));
        }
    }
}
