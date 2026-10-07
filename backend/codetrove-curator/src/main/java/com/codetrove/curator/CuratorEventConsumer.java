package com.codetrove.curator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "codetrove.eventing.enabled", havingValue = "true")
@ConditionalOnProperty(name = "codetrove.curator.enabled", havingValue = "true")
class CuratorEventConsumer {

    private final CuratorEventProcessor processor;

    CuratorEventConsumer(CuratorEventProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(
        topics = "codetrove.curator.commands.v1",
        groupId = "${codetrove.eventing.curator-consumer-group:codetrove-curator-v1}"
    )
    public void consume(String payload) {
        processor.process(payload);
    }
}
