package com.rageval.workbench.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sizing of the bounded run-execution worker pool (spec §15/§25). One submitted task processes one
 * whole run; {@code workerCount} therefore bounds how many runs execute concurrently and {@code
 * queueCapacity} how many can wait. Per-target concurrency is not configured here — it lives on
 * each {@code TargetSystem.maximumConcurrency} and is enforced by {@code TargetConcurrencyLimiter}.
 */
@ConfigurationProperties(prefix = "rag-evaluation.execution")
public record ExecutionProperties(
        @DefaultValue("2") int workerCount,
        @DefaultValue("100") int queueCapacity,
        @DefaultValue("3") int defaultRepetitions,
        @DefaultValue("30") int shutdownGraceSeconds) {
}
