package com.rageval.workbench.evaluation.llm;

import java.util.Objects;

/** One structured judge call: a hardened system prompt plus the metric's user prompt. */
public record JudgeRequest(String systemPrompt, String userPrompt) {

    public JudgeRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        Objects.requireNonNull(userPrompt, "userPrompt must not be null");
    }
}
