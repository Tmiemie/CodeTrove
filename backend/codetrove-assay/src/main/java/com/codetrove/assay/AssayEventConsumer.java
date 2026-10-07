package com.codetrove.assay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "codetrove.eventing.enabled", havingValue = "true")
@ConditionalOnProperty(name = "codetrove.assay.enabled", havingValue = "true")
class AssayEventConsumer {

    private final AssayEventProcessor processor;

    AssayEventConsumer(AssayEventProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(
        topics = "codetrove.assay.commands.v1",
        groupId = "${codetrove.eventing.assay-consumer-group:codetrove-assay-v1}"
    )
    public void consume(String payload) {
        processor.process(payload);
    }
}
