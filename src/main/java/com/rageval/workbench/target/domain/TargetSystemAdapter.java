package com.rageval.workbench.target.domain;

/** Invokes an external chatbot/RAG system, or a stand-in for one, and returns a normalized result. */
public interface TargetSystemAdapter {

    AdapterType adapterType();

    TargetInvocationResult invoke(TargetSystemConfiguration configuration, TargetInvocationRequest request);
}
