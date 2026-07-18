package com.rageval.workbench.evaluation.llm;

/** The judge cannot be reached at all: Ollama is down/unreachable or the model is not pulled. */
public class JudgeUnavailableException extends JudgeException {

    public JudgeUnavailableException(String message) {
        super(message);
    }

    public JudgeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
