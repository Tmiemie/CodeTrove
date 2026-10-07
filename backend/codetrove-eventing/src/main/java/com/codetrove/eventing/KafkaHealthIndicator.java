package com.codetrove.eventing;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component("kafka")
@ConditionalOnProperty(name = "codetrove.eventing.enabled", havingValue = "true")
class KafkaHealthIndicator implements HealthIndicator, DisposableBean {

    private final AdminClient adminClient;

    KafkaHealthIndicator(
        @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers
    ) {
        this.adminClient = AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
            bootstrapServers
        ));
    }

    @Override
    public Health health() {
        try {
            String clusterId = adminClient.describeCluster().clusterId().get(3, TimeUnit.SECONDS);
            return Health.up().withDetail("clusterId", clusterId).build();
        } catch (Exception exception) {
            return Health.down()
                .withDetails(Map.of("error", exception.getClass().getSimpleName()))
                .build();
        }
    }

    @Override
    public void destroy() {
        adminClient.close();
    }
}
