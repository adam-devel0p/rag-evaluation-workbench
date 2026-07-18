package com.rageval.workbench.evaluation.llm;

/**
 * The judge answered, but the answer could not be mapped onto the requested schema. Spec §14
 * treats malformed or incomplete evaluator output as a human-review trigger, which the FAILED
 * metric result carries.
 */
public class JudgeMalformedOutputException extends JudgeException {

    public JudgeMalformedOutputException(String message) {
        super(message);
    }

    public JudgeMalformedOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
