package com.codetrove.repository;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/** Single-node repository write serialization shared by push and merge operations. */
@Component
public class RepositoryWriteLockService {

    private final ConcurrentHashMap<Long, Semaphore> locks = new ConcurrentHashMap<>();

    public Lease acquire(long repositoryId) {
        Semaphore semaphore = locks.computeIfAbsent(repositoryId, ignored -> new Semaphore(1, true));
        semaphore.acquireUninterruptibly();
        return new Lease(semaphore);
    }

    public <T> T withLock(long repositoryId, Supplier<T> operation) {
        try (Lease ignored = acquire(repositoryId)) {
            return operation.get();
        }
    }

    public static final class Lease implements AutoCloseable {
        private final Semaphore semaphore;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }
}
