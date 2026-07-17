package com.rageval.workbench.run.application;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * Enforces each target's {@code maximumConcurrency} across all concurrently executing runs: a
 * worker must hold a permit for the target while one invocation is in flight. Permits are sized
 * from the target's configuration the first time that target is seen after startup; a changed
 * {@code maximumConcurrency} therefore applies from the next application start (acceptable for a
 * single-consultant workbench, and documented on the target API).
 */
@Component
public class TargetConcurrencyLimiter {

    private final Map<UUID, Semaphore> semaphoresByTargetId = new ConcurrentHashMap<>();

    public void acquire(UUID targetSystemId, int maximumConcurrency) throws InterruptedException {
        semaphore(targetSystemId, maximumConcurrency).acquire();
    }

    public void release(UUID targetSystemId, int maximumConcurrency) {
        semaphore(targetSystemId, maximumConcurrency).release();
    }

    private Semaphore semaphore(UUID targetSystemId, int maximumConcurrency) {
        return semaphoresByTargetId.computeIfAbsent(
                targetSystemId, id -> new Semaphore(Math.max(1, maximumConcurrency), true));
    }
}
