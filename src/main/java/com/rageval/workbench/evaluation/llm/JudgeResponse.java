package com.rageval.workbench.evaluation.llm;

import java.util.Objects;

/** A successful judge call: the schema-mapped output plus the call metadata spec §12 persists. */
public record JudgeResponse<T>(T output, String modelName, long durationMilliseconds) {

    public JudgeResponse {
        Objects.requireNonNull(output, "output must not be null");
        Objects.requireNonNull(modelName, "modelName must not be null");
    }
}
