package site.yuqi.media.intake;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import site.yuqi.media.db.MediaRepository;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Same consumer discipline as the indexers: manual acknowledgement, a few backed-off retries for
 * trouble that may pass, and quarantine for records that can never be processed. A quarantined
 * record is committed past so that one bad message cannot block the partition.
 */
@Configuration
@ConditionalOnProperty(name = "portfolio.media-pipeline.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, WorkEvent> mediaWorkConsumerFactory(KafkaProperties properties,
                                                                      ObjectProvider<SslBundles> sslBundles) {
        Map<String, Object> config = properties.buildConsumerProperties(sslBundles.getIfAvailable());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        // The producer is another service with its own class names: never trust its type headers.
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, WorkEvent.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, WorkEvent.class.getPackageName());
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, WorkEvent> mediaWorkListenerContainerFactory(
            ConsumerFactory<String, WorkEvent> mediaWorkConsumerFactory, MediaRepository repository,
            ObjectMapper mapper) {
        ConcurrentKafkaListenerContainerFactory<String, WorkEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(mediaWorkConsumerFactory);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        ExponentialBackOffWithMaxRetries backoff = new ExponentialBackOffWithMaxRetries(4);
        backoff.setInitialInterval(1000);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(10000);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, error) -> {
            try {
                repository.quarantine(record.topic(), record.partition(), record.offset(),
                        payload(record.value(), error, mapper), rootCause(error).getClass().getSimpleName());
            } catch (Exception failedQuarantine) {
                throw new IllegalStateException("Quarantine failed; source offset must not be committed",
                        failedQuarantine);
            }
        }, backoff);
        handler.addNotRetryableExceptions(WorkIntake.PoisonEventException.class);
        handler.setCommitRecovered(true);
        factory.setCommonErrorHandler(handler);
        return factory;
    }

    /** What to keep for a human: the parsed event, or the raw bytes when it would not parse at all. */
    private static String payload(Object value, Throwable error, ObjectMapper mapper) throws Exception {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof DeserializationException unreadable && unreadable.getData() != null) {
                return new String(unreadable.getData(), StandardCharsets.UTF_8);
            }
        }
        return mapper.writeValueAsString(value);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause;
    }
}
