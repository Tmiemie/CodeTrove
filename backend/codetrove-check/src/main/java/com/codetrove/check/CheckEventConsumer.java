package com.codetrove.check;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "codetrove.eventing.enabled", havingValue = "true")
class CheckEventConsumer {

    private final CheckEventProcessor processor;

    CheckEventConsumer(CheckEventProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(
        topics = {
            "codetrove.mr.events.v1",
            "codetrove.curator.results.v1",
            "codetrove.assay.results.v1"
        },
        groupId = "${codetrove.eventing.check-consumer-group:codetrove-check-v1}"
    )
    public void consume(String payload) {
        processor.process(payload);
    }
}
