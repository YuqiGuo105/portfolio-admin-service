package site.yuqi.admin.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Declares the replayable operations topic when the broker disables implicit topic creation. */
@Configuration
public class OperationsKafkaTopicConfig {

    @Bean
    NewTopic operationEventsTopic(
            @Value("${portfolio.kafka.topics.operations:platform.operation.events.v1}") String topic,
            @Value("${portfolio.operations.topic.partitions:1}") int partitions,
            @Value("${portfolio.operations.topic.replicas:1}") int replicas) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }
}
