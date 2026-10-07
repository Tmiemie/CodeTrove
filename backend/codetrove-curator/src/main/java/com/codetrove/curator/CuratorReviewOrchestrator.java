package com.codetrove.curator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class CuratorReviewOrchestrator {

    private final List<ReviewSkill> skills;
    private final ReviewJudge judge;
    private final ExecutorService executor;
    private final Duration timeout;
    private final Map<String, Retry> retries;
    private final Map<String, CircuitBreaker> circuitBreakers;
    private final Map<String, Bulkhead> bulkheads;

    CuratorReviewOrchestrator(
        List<ReviewSkill> skills,
        ReviewJudge judge,
        @Value("${codetrove.curator.parallelism:2}") int parallelism,
        @Value("${codetrove.curator.skill-timeout:3s}") Duration timeout,
        @Value("${codetrove.curator.max-attempts:2}") int maxAttempts
    ) {
        if (skills.isEmpty() || parallelism <= 0 || timeout.isNegative() || timeout.isZero()
            || maxAttempts <= 0) {
            throw new IllegalArgumentException("Invalid Curator execution configuration");
        }
        this.skills = List.copyOf(skills);
        this.judge = judge;
        this.executor = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "codetrove-curator-skill");
            thread.setDaemon(true);
            return thread;
        });
        this.timeout = timeout;
        RetryConfig retryConfig = RetryConfig.custom()
            .maxAttempts(maxAttempts)
            .waitDuration(Duration.ofMillis(50))
            .retryOnException(exception -> !(exception instanceof IllegalArgumentException))
            .build();
        CircuitBreakerConfig circuitConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .minimumNumberOfCalls(2)
            .slidingWindowSize(4)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .build();
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
            .maxConcurrentCalls(1)
            .maxWaitDuration(Duration.ZERO)
            .build();
        this.retries = skills.stream().collect(Collectors.toUnmodifiableMap(
            ReviewSkill::name,
            skill -> Retry.of("curator-" + skill.name().toLowerCase(java.util.Locale.ROOT), retryConfig)
        ));
        this.circuitBreakers = skills.stream().collect(Collectors.toUnmodifiableMap(
            ReviewSkill::name,
            skill -> CircuitBreaker.of(
                "curator-" + skill.name().toLowerCase(java.util.Locale.ROOT),
                circuitConfig
            )
        ));
        this.bulkheads = skills.stream().collect(Collectors.toUnmodifiableMap(
            ReviewSkill::name,
            skill -> Bulkhead.of("curator-" + skill.name().toLowerCase(java.util.Locale.ROOT), bulkheadConfig)
        ));
    }

    List<ReviewFinding> review(ReviewInput input) {
        List<CompletableFuture<List<ReviewFindingCandidate>>> futures = skills.stream()
            .map(skill -> CompletableFuture.supplyAsync(() -> execute(skill, input), executor)
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS))
            .toList();
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            List<ReviewFindingCandidate> candidates = new ArrayList<>();
            for (CompletableFuture<List<ReviewFindingCandidate>> future : futures) {
                candidates.addAll(future.join());
            }
            return judge.judge(input, candidates);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancel(futures);
            throw new CuratorSkillUnavailableException("Curator review was interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            cancel(futures);
            throw new CuratorSkillUnavailableException("Curator review skill unavailable", exception);
        }
    }

    private List<ReviewFindingCandidate> execute(ReviewSkill skill, ReviewInput input) {
        Supplier<List<ReviewFindingCandidate>> supplier = () -> skill.review(input);
        supplier = Bulkhead.decorateSupplier(bulkheads.get(skill.name()), supplier);
        supplier = CircuitBreaker.decorateSupplier(circuitBreakers.get(skill.name()), supplier);
        supplier = Retry.decorateSupplier(retries.get(skill.name()), supplier);
        return supplier.get();
    }

    private void cancel(List<CompletableFuture<List<ReviewFindingCandidate>>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
