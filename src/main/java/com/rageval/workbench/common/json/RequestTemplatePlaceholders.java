package com.rageval.workbench.common.json;

/** Exact placeholder tokens recognized inside a {@link com.rageval.workbench.target.domain.TargetSystem} request template. */
public final class RequestTemplatePlaceholders {

    public static final String QUESTION = "{{question}}";
    public static final String CONVERSATION_ID = "{{conversationId}}";
    public static final String TEST_CASE_ID = "{{testCaseId}}";
    public static final String USER_CONTEXT = "{{userContext}}";

    private RequestTemplatePlaceholders() {
    }
}
