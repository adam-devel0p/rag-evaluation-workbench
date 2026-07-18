package com.rageval.workbench.classification.application;

import com.rageval.workbench.classification.domain.EvaluationFinding;
import com.rageval.workbench.classification.domain.FailureCategory;
import com.rageval.workbench.classification.infrastructure.EvaluationFindingRepository;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.evaluation.application.RepetitionConsistencyAnalyzer;
import com.rageval.workbench.evaluation.deterministic.AnswerabilityEvaluator;
import com.rageval.workbench.evaluation.deterministic.CitationPresenceEvaluator;
import com.rageval.workbench.evaluation.deterministic.CitationRetrievalConsistencyEvaluator;
import com.rageval.workbench.evaluation.deterministic.ExpectedDocumentEvaluator;
import com.rageval.workbench.evaluation.deterministic.ForbiddenClaimsEvaluator;
import com.rageval.workbench.evaluation.deterministic.LatencyEvaluator;
import com.rageval.workbench.evaluation.deterministic.RefusalEvaluator;
import com.rageval.workbench.evaluation.deterministic.RequiredFactsEvaluator;
import com.rageval.workbench.evaluation.deterministic.TransportEvaluator;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.llm.ContextRelevanceEvaluator;
import com.rageval.workbench.evaluation.llm.GroundednessEvaluator;
import com.rageval.workbench.evaluation.llm.ReferenceCorrectnessEvaluator;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.TestExecution;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import com.rageval.workbench.run.infrastructure.TestExecutionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The evidence-based failure classification rules of spec §16, applied after deterministic
 * evaluation and before any LLM classifier exists. Every finding names its category, explains
 * itself, links the supporting metric results, states a confidence, and says whether a human must
 * confirm it. Where the black-box data only proves a symptom, the finding says exactly that
 * ("Root cause: undetermined from black-box data") instead of guessing a category — that honest
 * UNKNOWN is itself a rule, not a fallback bug.
 */
@Service
public class FailureClassificationService {

    private static final Logger log = LoggerFactory.getLogger(FailureClassificationService.class);

    private static final String UNDETERMINED = "Root cause: undetermined from black-box data";

    private final EvaluationRunRepository runRepository;
    private final TestExecutionRepository executionRepository;
    private final EvaluationTestCaseRepository testCaseRepository;
    private final EvaluationMetricResultRepository metricResultRepository;
    private final EvaluationFindingRepository findingRepository;
    private final RepetitionConsistencyAnalyzer consistencyAnalyzer;

    public FailureClassificationService(
            EvaluationRunRepository runRepository,
            TestExecutionRepository executionRepository,
            EvaluationTestCaseRepository testCaseRepository,
            EvaluationMetricResultRepository metricResultRepository,
            EvaluationFindingRepository findingRepository,
            RepetitionConsistencyAnalyzer consistencyAnalyzer) {
        this.runRepository = runRepository;
        this.executionRepository = executionRepository;
        this.testCaseRepository = testCaseRepository;
        this.metricResultRepository = metricResultRepository;
        this.findingRepository = findingRepository;
        this.consistencyAnalyzer = consistencyAnalyzer;
    }

    /**
     * (Re)classifies a whole run: existing findings are replaced so a resumed or re-evaluated run
     * never accumulates stale duplicates.
     */
    @Transactional
    public void classifyRun(UUID runId) {
        EvaluationRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            log.warn("Run {} vanished before classification", runId);
            return;
        }
        findingRepository.deleteAll(findingRepository.findByEvaluationRunId(runId));

        List<TestExecution> executions = executionRepository.findByEvaluationRunId(runId);
        Map<UUID, EvaluationTestCase> testCases = testCaseRepository.findByDatasetId(run.getDatasetId()).stream()
                .collect(Collectors.toMap(EvaluationTestCase::getId, testCase -> testCase));
        Map<UUID, List<EvaluationMetricResult>> resultsByExecution =
                metricResultRepository.findByRunId(runId).stream()
                        .collect(Collectors.groupingBy(EvaluationMetricResult::getTestExecutionId));

        List<EvaluationFinding> findings = new ArrayList<>();
        for (TestExecution execution : executions) {
            EvaluationTestCase testCase = testCases.get(execution.getTestCaseId());
            if (testCase == null) {
                continue;
            }
            findings.addAll(classifyExecution(
                    run, testCase, execution, resultsByExecution.getOrDefault(execution.getId(), List.of())));
        }
        findings.addAll(classifyConsistency(run, executions, testCases, resultsByExecution));
        findingRepository.saveAll(findings);
        log.info("Run {}: classification produced {} finding(s)", runId, findings.size());
    }

    /** Applies the per-execution rules; package-visible for direct unit testing. */
    List<EvaluationFinding> classifyExecution(
            EvaluationRun run,
            EvaluationTestCase testCase,
            TestExecution execution,
            List<EvaluationMetricResult> results) {
        Map<String, EvaluationMetricResult> byKey = new HashMap<>();
        results.forEach(result -> byKey.putIfAbsent(result.getEvaluatorKey(), result));
        List<EvaluationFinding> findings = new ArrayList<>();

        boolean transportProblem = ruleTargetSystemError(run, testCase, execution, byKey, findings);
        ruleHighLatency(run, testCase, execution, byKey, findings);
        if (!transportProblem) {
            boolean retrievalExplained = ruleRetrieval(run, testCase, execution, byKey, findings);
            ruleRefusal(run, testCase, execution, byKey, findings);
            ruleForbiddenClaims(run, testCase, execution, byKey, findings, retrievalExplained);
            ruleCitationConsistency(run, testCase, execution, byKey, findings);
            ruleCitationPresence(run, testCase, execution, byKey, findings);
            ruleAnswerability(run, testCase, execution, byKey, findings);
            ruleUnsupportedClaims(run, testCase, execution, byKey, findings, retrievalExplained);
            ruleIrrelevantContext(run, testCase, execution, byKey, findings);
            ruleGroundedButWrongAgainstReference(run, testCase, execution, byKey, findings);
        }
        ruleUnexplainedFailure(run, testCase, execution, byKey, results, findings);
        return findings;
    }

    /** Execution-level target failure (or a 2xx with no usable answer, which the transport metric catches). */
    private boolean ruleTargetSystemError(
            EvaluationRun run,
            EvaluationTestCase testCase,
            TestExecution execution,
            Map<String, EvaluationMetricResult> byKey,
            List<EvaluationFinding> findings) {
        EvaluationMetricResult transport = byKey.get(TransportEvaluator.KEY);
        boolean executionFailed = execution.getStatus() == ExecutionStatus.TIMED_OUT
                || execution.getStatus() == ExecutionStatus.TARGET_ERROR
                || execution.getStatus() == ExecutionStatus.INVALID_RESPONSE;
        boolean transportFailed = completedAndFailed(transport);
        if (!executionFailed && !transportFailed) {
            return false;
        }
        String detail = executionFailed
                ? "The invocation ended " + execution.getStatus()
                        + (execution.getErrorType() != null ? " (" + execution.getErrorType() + ")" : "")
                        + (execution.getHttpStatus() != null ? ", HTTP " + execution.getHttpStatus() : "")
                : "The invocation returned successfully but no usable answer was extracted";
        findings.add(finding(
                run,
                testCase,
                execution,
                FailureCategory.TARGET_SYSTEM_ERROR,
                "Target system error on " + testCase.getExternalId(),
                detail + ". This is a transport-level failure of the target system; answer quality was not judged",
                detail,
                "Investigate the target system's availability and error handling for this request",
                "The request completes successfully with a non-blank answer",
                evidenceOf(transport),
                1.0,
                false,
                supportingIds(transport)));
        return true;
    }

    private void ruleHighLatency(
            EvaluationRun run,
            EvaluationTestCase testCase,
            TestExecution execution,
            Map<String, EvaluationMetricResult> byKey,
            List<EvaluationFinding> findings) {
        EvaluationMetricResult latency = byKey.get(LatencyEvaluator.KEY);
        if (!completedAndFailed(latency)) {
            return;
        }
        findings.add(finding(
                run,
                testCase,
                execution,
                FailureCategory.HIGH_LATENCY,
                "High latency on " + testCase.getExternalId(),
                latency.getExplanation(),
                "Measured latency exceeded the configured maximum: " + latency.getExplanation(),
                "Investigate the target system's latency for this class of question",
                "The answer arrives within the configured maximum acceptable latency",
                evidenceOf(latency),
                1.0,
                false,
                supportingIds(latency)));
    }

    /**
     * Retrieval rules (spec §16): expected document absent → RETRIEVAL_MISS; expected document
     * retrieved but not first while the answer is wrong → RETRIEVAL_RANKING (possibly
     * OUTDATED_DOCUMENT — a human must decide, black-box data cannot). Returns true when a
     * retrieval-side category already explains a wrong answer.
     */
    private boolean ruleRetrieval(
            EvaluationRun run,
            EvaluationTestCase testCase,
            TestExecution execution,
            Map<String, EvaluationMetricResult> byKey,
            List<EvaluationFinding> findings) {
        EvaluationMetricResult expectedDocuments = byKey.get(ExpectedDocumentEvaluator.KEY);
        if (expectedDocuments == null || expectedDocuments.getStatus() != MetricResultStatus.COMPLETED) {
            return false;
        }
        if (Boolean.FALSE.equals(expectedDocuments.getPassed())) {
            findings.add(finding(
                    run,
                    testCase,
                    execution,
                    FailureCategory.RETRIEVAL_MISS,
                    "Retrieval miss on " + testCase.getExternalId(),
                    "None of the expected document(s) appear in the retrieved chunks: "
                            + expectedDocuments.getExplanation(),
                    "The retrieval layer did not surface the expected document(s) "
                            + testCase.getExpectedDocumentIds(),
                    "Check whether the expected document is indexed and retrievable for this question",
                    "At least one expected document appears in the retrieved chunks",
                    evidenceOf(expectedDocuments),
                    0.9,
                    false,
                    supportingIds(expectedDocuments)));
            return true;
        }
        boolean hitAt1 = retrievalFlag(expectedDocuments, ExpectedDocumentEvaluator.HIT_AT_1_KEY);
        EvaluationMetricResult requiredFacts = byKey.get(RequiredFactsEvaluator.KEY);
        EvaluationMetricResult forbiddenClaims = byKey.get(ForbiddenClaimsEvaluator.KEY);
        boolean wrongAnswer = completedAndFailed(requiredFacts) || completedAndFailed(forbiddenClaims);
        if (!hitAt1 && wrongAnswer) {
            List<EvaluationEvidence> evidence = new ArrayList<>(evidenceOf(expectedDocuments));
            evidence.addAll(evidenceOf(requiredFacts));
            evidence.addAll(evidenceOf(forbiddenClaims));
            findings.add(finding(
                    run,
                    testCase,
                    execution,
                    FailureCategory.RETRIEVAL_RANKING,
                    "Retrieval ranking problem on " + testCase.getExternalId(),
                    "An expected document was retrieved but not ranked first, and the answer is wrong "
                            + "(missing mandatory facts or containing forbidden claims). The answer may follow a "
                            + "higher-ranked, possibly outdated document — whether this is RETRIEVAL_RANKING or "
                            + "OUTDATED_DOCUMENT cannot be decided from black-box data and needs human confirmation",
                    "Expected document present but outranked; wrong answer plausibly follows a higher-ranked document",
                    "Inspect the higher-ranked chunks; if they come from an outdated document, fix the corpus, "
                            + "otherwise tune ranking",
                    "The expected document ranks first and the answer states the required facts",
                    evidence,
                    0.5,
                    true,
                    supportingIds(expectedDocuments, requiredFacts, forbiddenClaims)));
            return true;
        }
        if (hitAt1 && completedAndFailed(requiredFacts)) {
            List<EvaluationEvidence> evidence = new ArrayList<>(evidenceOf(requiredFacts));
            evidence.addAll(evidenceOf(expectedDocuments));
            findings.add(finding(
                    run,
                    testCase,
                    execution,
                    FailureCategory.INCOMPLETE_ANSWER,
                    "Incomplete answer despite correct retrieval on " + testCase.getExternalId(),
                    "The expected document was retrieved at rank 1, yet mandatory required fact(s) are missing "
                            + "from the answer: " + requiredFacts.getExplanation()
                            + ". Whether the generation step dropped available facts needs human confirmation",
                    "Retrieval looks correct; the generation step likely omitted required content",
                    "Review whether the answer synthesis uses the full retrieved context",
                    "The answer states every mandatory required fact",
                    evidence,
                    0.6,
                    true,
                    supportingIds(requiredFacts, expectedDocuments)));
            return true;
        }
        return false;
    }

    private void ruleRefusal(
            EvaluationRun run,
            EvaluationTestCase testCase,
            TestExecution execution,
            Map<String, EvaluationMetricResult> byKey,
            List<EvaluationFinding> findings) {
        EvaluationMetricResult refusal = byKey.get(RefusalEvaluator.KEY);
        if (!completedAndFailed(refusal)) {
            return;
        }
        String classification = refusalClassification(refusal);
        if (RefusalEvaluator.Classification.EMPTY_OR_ERROR.name().equals(classification)) {
            return; // already covered by the target-system-error rule
        }
        boolean disclosure = refusal.getEvidence().stream()
                .anyMatch(evidence -> "unsafe-disclosure".equals(evidence.type()));
        findings.add(finding(
                run,
                testCase,
                execution,
                FailureCategory.FAILED_REFUSAL,
                "Failed refusal on " + testCase.getExternalId(),
                refusal.getExplanation(),
                "A refusal was required but the system "
                        + (RefusalEvaluator.Classification.DIRECT_ANSWER_WHEN_REFUSAL_REQUIRED.name()
                                        .equals(classification)
                                ? "answered directly"
                                : "refused and then continued answering"),
                "Harden the target's refusal behavior for this class of question",
                "The system answers with a clear refusal and discloses nothing",
                evidenceOf(refusal),
                disclosure ? 0.85 : 0.6,
                true,
                supportingIds(refusal)));
        if (disclosure) {
            findings.add(finding(
                    run,
                    testCase,
                    execution,
                    FailureCategory.UNSAFE_DISCLOSURE,
                    "Unsafe disclosure on " + testCase.getExternalId(),
                    "Forbidden content appeared in the answer although a refusal was required — see the matched "
                            + "forbidden claim evidence",
                    "The system disclosed content the test case marks as forbidden",
                    "Treat as a safety incident for the target system; verify guardrails on sensitive data",
                    "No forbidden content appears in any answer to this question",
                    refusal.getEvidence().stream()
                            .filter(evidence -> "unsafe-disclosure".equals(evidence.type()))
                            .toList(),
                    0.85,
                    true,
                    supportingIds(refusal)));
        }
    }

    private void ruleForbiddenClaims(
            EvaluationRun run,
            EvaluationTestCase testCase,
            TestExecution execution,
            Map<String, EvaluationMetricResult> byKey,
            List<EvaluationFinding> findings,
            boolean retrievalExplained) {
        if (testCase.isShouldRefuse() || retrievalExplained) {
            return; // refusal rules or a retrieval-side category already own this failure
        }
        EvaluationMetricResult forbiddenClaims = byKey.get(ForbiddenClaimsEvaluator.KEY);
        if (!completedAndFailed(forbiddenClaims)) {
            return;
        }
        EvaluationMetricResult expectedDocuments = byKey.get(ExpectedDocumentEvaluator.KEY);
        boolean retrievalKnownGood = expectedDocuments != null
                && expectedDocuments.getStatus() == MetricResultStatus.COMPLETED
                && retrievalFlag(expectedDocuments, ExpectedDocumentEvaluator.HIT_AT_1_KEY);
        findings.add(finding(
                run,
                testCase,
                execution,
                FailureCategory.INCORRECT_ANSWER,
                "Incorrect answer on " + testCase.getExternalId(),
                "The answer contains claim(s) the test case marks as forbidden/known-wrong: "
                        + forbiddenClaims.getExplanation()
                        + (retrievalKnownGood
                                ? ". The correct document was retrieved at rank 1, so the answer contradicts "
                                        + "available context"
                                : ". " + UNDETERMINED + " beyond the incorrect content itself"),
                retrievalKnownGood
                        ? "Correct context was retrieved but the answer contradicts it"
                        : "Observed: incorrect answer content. " + UNDETERMINED,
                "Verify why the generation step produced known-wrong content for this question",
                "The answer contains none of the forbidden claims",
                evidenceOf(forbiddenClaims),
                retrievalKnownGood ? 0.8 : 0.6,
                !retrievalKnownGood,
                supportingIds(forbiddenClaims, expectedDocuments)));
    }

    private void ruleCitationConsistency(
            EvaluationRun run,
            EvaluationTestCase testCase,
            TestExecution execution,
            Map<String, EvaluationMetricResult> byKey,
            List<EvaluationFinding> findings) {
        EvaluationMetricResult consistency = byKey.get(CitationRetrievalConsistencyEvaluator.KEY);
        if (!completedAndFailed(consistency)) {
            return;
        }
        findings.add(finding(
                run,
                testCase,
                execution,
                FailureCategory.INCORRECT_CITATION,
                "Citation to unretrieved document on " + testCase.getExternalId(),
                consistency.getExplanation(),
                "The system cited document(s) that appear in no retrieved chunk — the citation cannot come from "
                        + "the retrieved context. This proves retrieval inconsistency, not whether the citation "
                        + "content is wrong",
                "Check how the target constructs citations; they should reference retrieved sources",
                "Every citation references a document present in the retrieved chunks",
                evidenceOf(consistency),
                0.8,
                false,
                supportingIds(consistency)));
    }

    private void ruleCitationPresence(
            EvaluationRun run,
            EvaluationTestCase testCase,
            TestExecution execution,
            Map<String, EvaluationMetricResult> byKey,
            List<EvaluationFinding> findings) {
        EvaluationMetricResult presence = byKey.get(CitationPresenceEvaluator.KEY);
        if (!completedAndFailed(presence)) {
            return;
        }
        boolean missing = presence.getEvidence().stream().anyMatch(evidence ->
                "citation-presence-summary".equals(evidence.type())
                        && evidence.metadata() != null
                        && Integer.valueOf(0)
                                .equals(evidence.metadata().get(CitationPresenceEvaluator.CITATION_COUNT_METADATA_KEY)));
        findings.add(finding(
                run,
                testCase,
                execution,
                missing ? FailureCategory.MISSING_CITATION : FailureCategory.INCORRECT_CITATION,
                (missing ? "Missing citation on " : "Citation policy violation on ") + testCase.getExternalId(),
                presence.getExplanation(),
                missing
                        ? "Citations are required for this test case but none were returned"
                        : "The returned citations violate the citation policy (duplicates, blank values, "
                                + "forbidden citations, or missing identifiers)",
                "Align the target's citation output with the citation policy",
                "The citation policy for this test case is satisfied",
                evidenceOf(presence),
                1.0,
                false,
                supportingIds(presence)));
    }

    private void ruleAnswerability(
            EvaluationRun run,
            EvaluationTestCase testCase,
            TestExecution execution,
            Map<String, EvaluationMetricResult> byKey,
            List<EvaluationFinding> findings) {
        if (testCase.isShouldRefuse()) {
            return; // the refusal rules own refusal-required cases
        }
        EvaluationMetricResult answerability = byKey.get(AnswerabilityEvaluator.KEY);
        if (!completedAndFailed(answerability)) {
            return;
        }
        findings.add(finding(
                run,
                testCase,
                execution,
                FailureCategory.UNSUPPORTED_CLAIM,
                "Confident answer to unanswerable question on " + testCase.getExternalId(),
                answerability.getExplanation(),
                "The question is marked unanswerable, yet the answer shows no deterministic non-answer signal — "
                        + "its content cannot be supported by documentation and is likely fabricated. Phrase-based "
                        + "detection needs human confirmation",
                "Verify the answer manually; if fabricated, harden the target against answering unanswerable "
                        + "questions",
                "The system signals that it cannot answer instead of fabricating content",
                evidenceOf(answerability),
                0.5,
                true,
                supportingIds(answerability)));
    }

    /**
     * Spec §16: "Answer contains claims not supported by any retrieved chunk → UNSUPPORTED_CLAIM."
     * Fires on a failed LLM groundedness verdict (Phase 5 evaluator), but only when a retrieval
     * category doesn't already explain the failure — an answer can't be grounded in chunks that
     * were never retrieved. Judge-based, so a human must confirm before this counts as a root
     * cause.
     */
    private void ruleUnsupportedClaims(
            EvaluationRun run,
            EvaluationTestCase testCase,
            TestExecution execution,
            Map<String, EvaluationMetricResult> byKey,
            List<EvaluationFinding> findings,
            boolean retrievalExplained) {
        EvaluationMetricResult groundedness = byKey.get(GroundednessEvaluator.KEY);
        if (retrievalExplained || !completedAndFailed(groundedness)) {
            return;
        }
        findings.add(finding(
                run,
                testCase,
                execution,
                FailureCategory.UNSUPPORTED_CLAIM,
                "Unsupported claim(s) on " + testCase.getExternalId(),
                "The answer contains claim(s) the groundedness judge found contradicted by or absent from the "
                        + "retrieved chunks: " + groundedness.getExplanation()
                        + ". If no source document contains this information at all, the underlying category may "
                        + "be MISSING_DOCUMENTATION — black-box data cannot distinguish the two",
                "The answer states content not supported by the retrieved context (LLM judge verdict — needs "
                        + "human confirmation)",
                "Verify the unsupported claims manually; if confirmed, constrain the generation step to the "
                        + "retrieved context",
                "Every factual claim in the answer is supported by a retrieved chunk or honestly qualified",
                evidenceOf(groundedness),
                confidenceCappedForJudge(groundedness),
                true,
                supportingIds(groundedness)));
    }

    /** Spec §6.8 IRRELEVANT_CONTEXT, from the Phase 5 per-chunk context-relevance verdicts. */
    private void ruleIrrelevantContext(
            EvaluationRun run,
            EvaluationTestCase testCase,
            TestExecution execution,
            Map<String, EvaluationMetricResult> byKey,
            List<EvaluationFinding> findings) {
        EvaluationMetricResult contextRelevance = byKey.get(ContextRelevanceEvaluator.KEY);
        if (!completedAndFailed(contextRelevance)) {
            return;
        }
        findings.add(finding(
                run,
                testCase,
                execution,
                FailureCategory.IRRELEVANT_CONTEXT,
                "Irrelevant retrieved context on " + testCase.getExternalId(),
                contextRelevance.getExplanation(),
                "The retrieval layer surfaced predominantly irrelevant chunks for this question (LLM judge "
                        + "per-chunk verdicts — needs human confirmation)",
                "Review the retrieved chunks manually; if confirmed irrelevant, improve retrieval filtering or "
                        + "ranking for this question class",
                "The majority of retrieved chunks are relevant to the question, with a relevant chunk in the "
                        + "top ranks",
                evidenceOf(contextRelevance),
                confidenceCappedForJudge(contextRelevance),
                true,
                supportingIds(contextRelevance)));
    }

    /**
     * The spec §14 calibration category "grounded in outdated context but wrong against
     * reference" made into a rule: reference correctness failed while groundedness passed. The
     * answer faithfully reflects its retrieved context, yet contradicts the reference — which
     * usually means the context itself is stale (OUTDATED_DOCUMENT) or the reference is; only a
     * human can decide which, so confirmation is mandatory.
     */
    private void ruleGroundedButWrongAgainstReference(
            EvaluationRun run,
            EvaluationTestCase testCase,
            TestExecution execution,
            Map<String, EvaluationMetricResult> byKey,
            List<EvaluationFinding> findings) {
        EvaluationMetricResult referenceCorrectness = byKey.get(ReferenceCorrectnessEvaluator.KEY);
        EvaluationMetricResult groundedness = byKey.get(GroundednessEvaluator.KEY);
        boolean grounded = groundedness != null
                && groundedness.getStatus() == MetricResultStatus.COMPLETED
                && Boolean.TRUE.equals(groundedness.getPassed());
        boolean alreadyExplained = findings.stream()
                .anyMatch(finding -> finding.getCategory() == FailureCategory.INCORRECT_ANSWER);
        if (!grounded || alreadyExplained || !completedAndFailed(referenceCorrectness)) {
            return;
        }
        List<EvaluationEvidence> evidence = new ArrayList<>(evidenceOf(referenceCorrectness));
        evidence.addAll(evidenceOf(groundedness));
        findings.add(finding(
                run,
                testCase,
                execution,
                FailureCategory.INCORRECT_ANSWER,
                "Grounded answer contradicts the reference on " + testCase.getExternalId(),
                "The answer is grounded in its retrieved context (groundedness passed) yet fails against the "
                        + "reference material: " + referenceCorrectness.getExplanation()
                        + ". A grounded answer that is wrong against the reference usually means the retrieved "
                        + "source is outdated (OUTDATED_DOCUMENT) — or the reference itself is stale. Black-box "
                        + "data cannot decide which; a human must confirm",
                "The answer faithfully reflects retrieved context that disagrees with the reference — "
                        + "possibly an outdated source document",
                "Compare the retrieved source document(s) against the reference material and update whichever "
                        + "is outdated",
                "The answer agrees with the (verified current) reference material",
                evidence,
                0.5,
                true,
                supportingIds(referenceCorrectness, groundedness)));
    }

    /** Judge-derived findings never claim more confidence than the judge result itself, capped at 0.6. */
    private static Double confidenceCappedForJudge(EvaluationMetricResult result) {
        Double confidence = result.getConfidence();
        return confidence != null ? Math.min(confidence, 0.6) : 0.6;
    }

    /**
     * The honest fallback of spec §16: a mandatory-metric failure that no rule above explained is
     * reported as UNKNOWN with the undetermined-root-cause wording, never silently dropped and
     * never forced into a guessed category.
     */
    private void ruleUnexplainedFailure(
            EvaluationRun run,
            EvaluationTestCase testCase,
            TestExecution execution,
            Map<String, EvaluationMetricResult> byKey,
            List<EvaluationMetricResult> results,
            List<EvaluationFinding> findings) {
        if (!findings.isEmpty()) {
            return;
        }
        List<EvaluationMetricResult> failedMetrics = results.stream()
                .filter(FailureClassificationService::completedAndFailed)
                .toList();
        if (failedMetrics.isEmpty()) {
            return;
        }
        String failedKeys = failedMetrics.stream()
                .map(EvaluationMetricResult::getEvaluatorKey)
                .collect(Collectors.joining(", "));
        List<EvaluationEvidence> evidence = new ArrayList<>();
        failedMetrics.forEach(result -> evidence.addAll(evidenceOf(result)));
        findings.add(finding(
                run,
                testCase,
                execution,
                FailureCategory.UNKNOWN,
                "Unexplained failure on " + testCase.getExternalId(),
                "Observed: failed metric(s) " + failedKeys + ". " + UNDETERMINED
                        + " — no classification rule matched the available evidence",
                "Observed symptom only. " + UNDETERMINED,
                "Review the failed metric evidence manually",
                "The failed metrics pass, or a human assigns a confirmed category",
                evidence,
                0.2,
                true,
                failedMetrics.stream().map(EvaluationMetricResult::getId).toList()));
    }

    private List<EvaluationFinding> classifyConsistency(
            EvaluationRun run,
            List<TestExecution> executions,
            Map<UUID, EvaluationTestCase> testCases,
            Map<UUID, List<EvaluationMetricResult>> resultsByExecution) {
        List<EvaluationFinding> findings = new ArrayList<>();
        Map<UUID, List<TestExecution>> byTestCase = executions.stream()
                .collect(Collectors.groupingBy(TestExecution::getTestCaseId, LinkedHashMap::new, Collectors.toList()));
        byTestCase.forEach((testCaseId, testCaseExecutions) -> {
            EvaluationTestCase testCase = testCases.get(testCaseId);
            if (testCase == null) {
                return;
            }
            RepetitionConsistencyAnalyzer.ConsistencyComparison comparison =
                    consistencyAnalyzer.compare(testCaseExecutions, resultsByExecution);
            if (!comparison.comparable() || comparison.consistent()) {
                return;
            }
            TestExecution anchor = testCaseExecutions.stream()
                    .filter(execution -> execution.getStatus() == ExecutionStatus.SUCCEEDED)
                    .findFirst()
                    .orElse(testCaseExecutions.get(0));
            List<EvaluationEvidence> evidence = comparison.differences().stream()
                    .map(difference -> new EvaluationEvidence(
                            "repetition-inconsistency", difference, null, null, null, null, Map.of()))
                    .toList();
            List<UUID> supportingMetricResultIds = comparison.signatures().stream()
                    .flatMap(signature -> resultsByExecution
                            .getOrDefault(signature.executionId(), List.of())
                            .stream())
                    .map(EvaluationMetricResult::getId)
                    .toList();
            findings.add(finding(
                    run,
                    testCase,
                    anchor,
                    FailureCategory.INCONSISTENT_RESPONSE,
                    "Inconsistent answers across repetitions of " + testCase.getExternalId(),
                    "Repetitions of the same question produced materially different fact-level behavior: "
                            + String.join("; ", comparison.differences())
                            + ". Wording variation is not compared — only fact matches, forbidden-claim matches, "
                            + "refusal behavior, cited documents, and retrieval ranks",
                    "The target answers the same question with materially different content across repetitions",
                    "Investigate nondeterminism in the target (sampling temperature, retrieval instability)",
                    "Repetitions of this question agree on facts, citations, and refusal behavior",
                    evidence,
                    0.8,
                    false,
                    supportingMetricResultIds));
        });
        return findings;
    }

    private static boolean completedAndFailed(EvaluationMetricResult result) {
        return result != null
                && result.getStatus() == MetricResultStatus.COMPLETED
                && Boolean.FALSE.equals(result.getPassed());
    }

    private static boolean retrievalFlag(EvaluationMetricResult expectedDocuments, String key) {
        for (EvaluationEvidence evidence : expectedDocuments.getEvidence()) {
            if ("retrieval-metrics".equals(evidence.type()) && evidence.metadata() != null) {
                return Boolean.TRUE.equals(evidence.metadata().get(key));
            }
        }
        return false;
    }

    private static String refusalClassification(EvaluationMetricResult refusal) {
        for (EvaluationEvidence evidence : refusal.getEvidence()) {
            if (evidence.metadata() != null
                    && evidence.metadata().get(RefusalEvaluator.CLASSIFICATION_METADATA_KEY) != null) {
                return evidence.metadata().get(RefusalEvaluator.CLASSIFICATION_METADATA_KEY).toString();
            }
        }
        return null;
    }

    private static List<EvaluationEvidence> evidenceOf(EvaluationMetricResult result) {
        return result != null ? result.getEvidence() : List.of();
    }

    private static List<UUID> supportingIds(EvaluationMetricResult... results) {
        List<UUID> ids = new ArrayList<>();
        for (EvaluationMetricResult result : results) {
            if (result != null) {
                ids.add(result.getId());
            }
        }
        return ids;
    }

    private static EvaluationFinding finding(
            EvaluationRun run,
            EvaluationTestCase testCase,
            TestExecution execution,
            FailureCategory category,
            String title,
            String summary,
            String likelyCause,
            String recommendation,
            String acceptanceCriteria,
            List<EvaluationEvidence> evidence,
            Double confidence,
            boolean requiresHumanConfirmation,
            List<UUID> supportingMetricResultIds) {
        return new EvaluationFinding(
                run.getId(),
                execution.getId(),
                category,
                testCase.getSeverity(),
                title,
                summary,
                likelyCause,
                recommendation,
                acceptanceCriteria,
                evidence,
                confidence,
                requiresHumanConfirmation,
                supportingMetricResultIds);
    }
}
