package com.codetrove.eventing;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "codetrove.eventing.enabled", havingValue = "true")
class KafkaEventingConfiguration {

    static final String DLQ_TOPIC = "codetrove.dlq.v1";

    @Bean
    NewTopic mergeRequestEventsTopic() {
        return topic(OutboxService.MR_EVENTS_TOPIC);
    }

    @Bean
    NewTopic curatorCommandsTopic() {
        return topic(OutboxService.CURATOR_COMMANDS_TOPIC);
    }

    @Bean
    NewTopic curatorResultsTopic() {
        return topic(OutboxService.CURATOR_RESULTS_TOPIC);
    }

    @Bean
    NewTopic assayCommandsTopic() {
        return topic(OutboxService.ASSAY_COMMANDS_TOPIC);
    }

    @Bean
    NewTopic assayResultsTopic() {
        return topic(OutboxService.ASSAY_RESULTS_TOPIC);
    }

    @Bean
    NewTopic deadLetterTopic() {
        return topic(DLQ_TOPIC);
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) -> new TopicPartition(DLQ_TOPIC, 0)
        );
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }
}
