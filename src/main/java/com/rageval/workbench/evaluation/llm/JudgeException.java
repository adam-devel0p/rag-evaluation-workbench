package com.rageval.workbench.evaluation.llm;

/**
 * A judge call failed. Always an evaluator failure (metric status {@code FAILED}), never a
 * chatbot failure.
 */
public class JudgeException extends RuntimeException {

    public JudgeException(String message) {
        super(message);
    }

    public JudgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
