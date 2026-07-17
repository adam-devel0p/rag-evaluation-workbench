package com.rageval.workbench.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Global defaults and hard limits for target invocation, applied uniformly across every {@link
 * com.rageval.workbench.target.domain.TargetSystem}. Per-target values (timeouts, redirects) live
 * on the entity itself; these properties bound the retry policy and response size, which are
 * deliberately not per-target configuration (spec §25's suggested defaults).
 */
@ConfigurationProperties(prefix = "rag-evaluation.target")
public record TargetProperties(
        @DefaultValue("2097152") long maximumResponseBytes, @DefaultValue Retry retry) {

    /**
     * {@code maximumAttempts} is the total number of call attempts (initial call included), not
     * the number of retries. The spec's suggested default of 2 means "one retry".
     */
    public record Retry(
            @DefaultValue("2") int maximumAttempts,
            @DefaultValue("200ms") Duration initialBackoff,
            @DefaultValue("2.0") double backoffMultiplier) {
    }
}
