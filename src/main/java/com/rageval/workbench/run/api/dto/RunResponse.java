package com.rageval.workbench.run.api.dto;

import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.RunMode;
import com.rageval.workbench.run.domain.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RunResponse(
        UUID id,
        UUID targetSystemId,
        UUID datasetId,
        String name,
        RunStatus status,
        RunMode runMode,
        int repetitionsPerTestCase,
        List<String> enabledEvaluatorKeys,
        Map<String, Object> evaluatorConfiguration,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        String failureReason) {

    public static RunResponse from(EvaluationRun run) {
        return new RunResponse(
                run.getId(),
                run.getTargetSystemId(),
                run.getDatasetId(),
                run.getName(),
                run.getStatus(),
                run.getRunMode(),
                run.getRepetitionsPerTestCase(),
                run.getEnabledEvaluatorKeys(),
                run.getEvaluatorConfiguration(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getCreatedAt(),
                run.getFailureReason());
    }
}
