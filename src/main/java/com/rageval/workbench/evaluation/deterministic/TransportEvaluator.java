package com.rageval.workbench.evaluation.deterministic;

import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.evaluation.domain.EvaluationStrategy;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.TestExecution;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Spec §11.1: did the invocation itself work — successful outcome, successful HTTP status, valid
 * response JSON, and a non-blank extracted answer. A failure here is a real chatbot/transport
 * failure (the target genuinely misbehaved); answer-quality evaluators separately skip such
 * executions with MISSING_REQUIRED_DATA rather than judging content that never arrived.
 */
@Component
public class TransportEvaluator implements EvaluationStrategy {

    public static final String KEY = "transport";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public EvaluationApplicability applicability(EvaluationContext context) {
        return EvaluationApplicability.applicable();
    }

    @Override
    public EvaluationResult evaluate(EvaluationContext context) {
        TestExecution execution = context.execution();
        List<String> problems = new ArrayList<>();
        if (execution.getStatus() != ExecutionStatus.SUCCEEDED) {
            problems.add("invocation ended " + execution.getStatus()
                    + (execution.getErrorType() != null ? " (" + execution.getErrorType() + ")" : ""));
        }
        Integer httpStatus = execution.getHttpStatus();
        if (httpStatus != null && (httpStatus < 200 || httpStatus > 299)) {
            problems.add("HTTP status " + httpStatus + " is not successful");
        }
        if (execution.getStatus() == ExecutionStatus.SUCCEEDED
                && (execution.getAnswer() == null || execution.getAnswer().isBlank())) {
            problems.add("no non-blank answer was extracted from the response");
        }

        boolean passed = problems.isEmpty();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("executionStatus", execution.getStatus().name());
        metadata.put("httpStatus", httpStatus);
        metadata.put("errorType", execution.getErrorType());
        metadata.put("errorMessage", execution.getErrorMessage());
        metadata.put("attemptCount", execution.getAttemptCount());
        metadata.put("answerLength", execution.getAnswer() != null ? execution.getAnswer().length() : 0);
        EvaluationEvidence evidence = new EvaluationEvidence(
                "transport",
                passed
                        ? "Invocation succeeded with a non-blank answer"
                        : String.join("; ", problems),
                null,
                null,
                null,
                null,
                metadata);
        return new EvaluationResult(
                passed,
                passed ? 1.0 : 0.0,
                1.0,
                passed
                        ? "Transport succeeded: outcome " + execution.getStatus()
                                + (httpStatus != null ? ", HTTP " + httpStatus : "") + ", non-blank answer extracted"
                        : "Transport failed: " + String.join("; ", problems),
                List.of(evidence),
                false);
    }
}
