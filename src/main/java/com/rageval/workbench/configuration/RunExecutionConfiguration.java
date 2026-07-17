package com.rageval.workbench.configuration;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The single bounded executor behind run orchestration (spec §15): fixed worker count, bounded
 * queue, abort-on-saturation (the caller translates {@code RejectedExecutionException} into an API
 * error instead of queueing unboundedly), and graceful shutdown that waits for in-flight run tasks.
 * Deliberately not the common ForkJoinPool and never handed unbounded {@code CompletableFuture}
 * chains.
 */
@Configuration
public class RunExecutionConfiguration {

    public static final String RUN_EXECUTOR_BEAN = "runExecutionExecutor";

    @Bean(name = RUN_EXECUTOR_BEAN)
    ThreadPoolTaskExecutor runExecutionExecutor(ExecutionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workerCount());
        executor.setMaxPoolSize(properties.workerCount());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("run-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.shutdownGraceSeconds());
        return executor;
    }
}
