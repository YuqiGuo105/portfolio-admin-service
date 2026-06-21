package site.yuqi.searchindexer.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import site.yuqi.searchindexer.events.ContentIndexEvent;

import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.listener.concurrency:2}")
    private int concurrency;

    @Bean
    public ConsumerFactory<String, ContentIndexEvent> consumerFactory(KafkaProperties props) {
        Map<String, Object> cfg = props.buildConsumerProperties();
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        cfg.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,
                org.apache.kafka.common.serialization.StringDeserializer.class);
        cfg.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);

        JsonDeserializer<ContentIndexEvent> value = new JsonDeserializer<>(ContentIndexEvent.class, false);
        value.addTrustedPackages("*");
        value.setUseTypeMapperForKey(false);

        return new DefaultKafkaConsumerFactory<>(cfg,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new ErrorHandlingDeserializer<>(value));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ContentIndexEvent>
            kafkaListenerContainerFactory(ConsumerFactory<String, ContentIndexEvent> cf) {
        ConcurrentKafkaListenerContainerFactory<String, ContentIndexEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
