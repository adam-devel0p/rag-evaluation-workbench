package com.rageval.workbench.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The spec §24 application metrics, published through one component so every tag decision sits in
 * one reviewable place. Tag cardinality rule (spec §23/§24): allowed tag values are enum-like and
 * bounded — outcome/status names, registered evaluator keys, exception class names. Never
 * question text, answers, client content, or per-entity identifiers (run/execution/test-case
 * IDs); those belong in structured logs (MDC), not metric tags.
 */
@Component
public class WorkbenchMetrics {

    private final MeterRegistry registry;

    public WorkbenchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One live target invocation (imported-trace and replay executions do not count). */
    public void recordTargetInvocation(String outcome, String errorType, Duration latency) {
        Counter.builder("rageval.target.invocations")
                .description("Live target-system invocations, by terminal outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        if (errorType != null) {
            Counter.builder("rageval.target.invocation.failures")
                    .description("Failed live target-system invocations, by error type")
                    .tag("outcome", outcome)
                    .tag("errorType", errorType)
                    .register(registry)
                    .increment();
        }
        Timer.builder("rageval.target.latency")
                .description("Live target-system invocation latency (includes retries)")
                .tag("outcome", outcome)
                .register(registry)
                .record(latency);
    }

    /** A test execution reaching a terminal status (live, imported, and replay alike). */
    public void recordTestExecution(String terminalStatus) {
        Counter.builder("rageval.test.executions")
                .description("Test executions reaching a terminal status")
                .tag("status", terminalStatus)
                .register(registry)
                .increment();
    }

    /** An evaluation run reaching a terminal status. */
    public void recordEvaluationRun(String terminalStatus) {
        Counter.builder("rageval.evaluation.runs")
                .description("Evaluation runs reaching a terminal status")
                .tag("status", terminalStatus)
                .register(registry)
                .increment();
    }

    /**
     * One evaluator execution. {@code status} is the metric-result status (COMPLETED/FAILED/
     * SKIPPED); FAILED means the evaluator itself broke and is additionally counted as an
     * evaluator failure — never as anything about the chatbot.
     */
    public void recordEvaluatorExecution(String evaluatorKey, String status, Duration duration) {
        Counter.builder("rageval.evaluator.executions")
                .description("Evaluator executions, by evaluator key and metric-result status")
                .tag("evaluator", evaluatorKey)
                .tag("status", status)
                .register(registry)
                .increment();
        if ("FAILED".equals(status)) {
            Counter.builder("rageval.evaluator.failures")
                    .description("Evaluator-infrastructure failures (evaluator broke; says nothing about the chatbot)")
                    .tag("evaluator", evaluatorKey)
                    .register(registry)
                    .increment();
        }
        Timer.builder("rageval.evaluator.duration")
                .description("Wall-clock duration of one evaluator execution")
                .tag("evaluator", evaluatorKey)
                .register(registry)
                .record(duration);
    }

    /** One LLM judge call. {@code failureType} is the exception class simple name, or null on success. */
    public void recordLlmJudgeCall(String failureType, Duration duration) {
        Counter.builder("rageval.llm.judge.calls")
                .description("Calls to the local Ollama judge model")
                .tag("result", failureType == null ? "success" : "failure")
                .register(registry)
                .increment();
        if (failureType != null) {
            Counter.builder("rageval.llm.judge.failures")
                    .description("Failed calls to the local Ollama judge model, by exception type")
                    .tag("exception", failureType)
                    .register(registry)
                    .increment();
        }
        Timer.builder("rageval.llm.judge.duration")
                .description("Wall-clock duration of one judge call")
                .register(registry)
                .record(duration);
    }

    /**
     * Registers the human-review backlog gauge. Called once by the review module (the count query
     * lives there; the metric name and description live here with every other metric).
     */
    public void registerReviewBacklogGauge(Supplier<Number> backlogCount) {
        Gauge.builder("rageval.review.backlog", backlogCount)
                .description("Metric results flagged for human review that have no review decision yet")
                .register(registry);
    }
}
