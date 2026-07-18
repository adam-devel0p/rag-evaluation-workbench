package com.rageval.workbench.evaluation.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The deterministic fake {@link JudgeClient} the normal test suite runs on — the default
 * {@code ./mvnw clean verify} never touches a real model. Tests script one responder per output
 * type (or start from {@link #withBenignDefaults()}), and every received request is recorded so
 * prompt-hardening assertions can inspect exactly what would be sent to a judge.
 */
public class FakeJudgeClient implements JudgeClient {

    public static final String MODEL_NAME = "fake-judge";

    /** One recorded judge call. */
    public record RecordedCall(JudgeRequest request, Class<?> responseType) {
    }

    private final Map<Class<?>, Function<JudgeRequest, ?>> responders = new HashMap<>();
    private final List<RecordedCall> calls = new ArrayList<>();
    private RuntimeException failure;

    /**
     * A fake whose eight responders return benign, deterministic happy-path outputs: one supported
     * claim, correct/complete/relevant answer, all chunks relevant, no refusal, no contradictions.
     */
    public static FakeJudgeClient withBenignDefaults() {
        FakeJudgeClient fake = new FakeJudgeClient();
        fake.on(ClaimExtractionOutput.class, request -> new ClaimExtractionOutput(
                List.of(new ExtractedClaim("c1", "benign extracted claim", "FACT"))));
        fake.on(GroundednessOutput.class, request -> new GroundednessOutput(
                List.of(new ClaimGroundingDecision(
                        "c1", "benign extracted claim", GroundingVerdict.SUPPORTED, List.of("chunk-1"), List.of(),
                        "stated by chunk-1")),
                1.0,
                true,
                "fully grounded",
                0.95));
        fake.on(ReferenceCorrectnessOutput.class, request -> new ReferenceCorrectnessOutput(
                true, 1.0, List.of("f1"), List.of(), List.of(), "matches the reference", 0.95));
        fake.on(CompletenessOutput.class, request -> new CompletenessOutput(
                true, 1.0, List.of("all material points"), List.of(), "complete", 0.95));
        fake.on(AnswerRelevanceOutput.class, request -> new AnswerRelevanceOutput(
                true, 1.0, List.of(), "directly addresses the question", 0.95));
        fake.on(ContextRelevanceOutput.class, request -> {
            List<ChunkRelevanceDecision> decisions = new ArrayList<>();
            for (String chunkId : chunkIdsFrom(request)) {
                decisions.add(new ChunkRelevanceDecision(chunkId, true, "on-topic"));
            }
            return new ContextRelevanceOutput(decisions, "all chunks relevant", 0.95);
        });
        fake.on(SemanticRefusalOutput.class, request -> new SemanticRefusalOutput(
                RefusalClassification.NO_REFUSAL, List.of(), "the answer simply answers", 0.95));
        fake.on(ContradictionOutput.class, request -> new ContradictionOutput(
                List.of(), "no contradictions", 0.95));
        return fake;
    }

    /** The chunk ids present in the request's untrusted-context BEGIN markers, in order. */
    public static List<String> chunkIdsFrom(JudgeRequest request) {
        List<String> chunkIds = new ArrayList<>();
        String prompt = request.userPrompt();
        int from = 0;
        while (true) {
            int begin = prompt.indexOf(UntrustedContent.CHUNK_BEGIN_PREFIX + "id=", from);
            if (begin < 0) {
                return chunkIds;
            }
            int idStart = begin + UntrustedContent.CHUNK_BEGIN_PREFIX.length() + "id=".length();
            int idEnd = prompt.indexOf(' ', idStart);
            chunkIds.add(prompt.substring(idStart, idEnd));
            from = idEnd;
        }
    }

    public <T> FakeJudgeClient on(Class<T> responseType, Function<JudgeRequest, T> responder) {
        responders.put(responseType, responder);
        return this;
    }

    /** Makes every subsequent call throw, e.g. a {@link JudgeUnavailableException}. */
    public FakeJudgeClient failingWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    public List<RecordedCall> calls() {
        return List.copyOf(calls);
    }

    public JudgeRequest lastRequest() {
        if (calls.isEmpty()) {
            throw new IllegalStateException("no judge call was recorded");
        }
        return calls.get(calls.size() - 1).request();
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    @Override
    public <T> JudgeResponse<T> judge(JudgeRequest request, Class<T> responseType) {
        calls.add(new RecordedCall(request, responseType));
        if (failure != null) {
            throw failure;
        }
        Function<JudgeRequest, ?> responder = responders.get(responseType);
        if (responder == null) {
            throw new IllegalStateException("No scripted fake response for " + responseType.getSimpleName());
        }
        @SuppressWarnings("unchecked")
        T output = (T) responder.apply(request);
        return new JudgeResponse<>(output, MODEL_NAME, 1L);
    }
}
