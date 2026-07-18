package com.rageval.workbench.classification.application;

import static com.rageval.workbench.evaluation.EvaluationFixtures.answerableTestCase;
import static com.rageval.workbench.evaluation.EvaluationFixtures.chunk;
import static com.rageval.workbench.evaluation.EvaluationFixtures.citation;
import static com.rageval.workbench.evaluation.EvaluationFixtures.context;
import static com.rageval.workbench.evaluation.EvaluationFixtures.fact;
import static com.rageval.workbench.evaluation.EvaluationFixtures.settings;
import static com.rageval.workbench.evaluation.EvaluationFixtures.succeededExecution;
import static com.rageval.workbench.evaluation.EvaluationFixtures.testCase;
import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.classification.domain.EvaluationFinding;
import com.rageval.workbench.classification.domain.FailureCategory;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.FactMatchType;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.EvaluationFixtures;
import com.rageval.workbench.evaluation.application.RepetitionConsistencyAnalyzer;
import com.rageval.workbench.evaluation.deterministic.CitationPresenceEvaluator;
import com.rageval.workbench.evaluation.deterministic.CitationRetrievalConsistencyEvaluator;
import com.rageval.workbench.evaluation.deterministic.ExpectedDocumentEvaluator;
import com.rageval.workbench.evaluation.deterministic.ForbiddenClaimsEvaluator;
import com.rageval.workbench.evaluation.deterministic.RefusalEvaluator;
import com.rageval.workbench.evaluation.deterministic.RequiredFactsEvaluator;
import com.rageval.workbench.evaluation.deterministic.TransportEvaluator;
import com.rageval.workbench.evaluation.domain.CitationPolicy;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.evaluation.domain.EvaluationStrategy;
import com.rageval.workbench.evaluation.domain.EvaluatorType;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.llm.ContextRelevanceEvaluator;
import com.rageval.workbench.evaluation.llm.GroundednessEvaluator;
import com.rageval.workbench.evaluation.llm.ReferenceCorrectnessEvaluator;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.TestExecution;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Spec §16 / §28.1 "failure classification rules": every rule is exercised against metric results
 * produced by the real evaluators (not hand-crafted evidence), so the rules and the evaluators
 * cannot drift apart unnoticed.
 */
class FailureClassificationServiceTest {

    private final FailureClassificationService service =
            new FailureClassificationService(null, null, null, null, null, new RepetitionConsistencyAnalyzer());

    private final EvaluationRun run = EvaluationFixtures.run();

    private EvaluationMetricResult evaluated(EvaluationStrategy strategy, EvaluationContext context) {
        EvaluationResult result = strategy.evaluate(context);
        return new EvaluationMetricResult(
                context.execution().getId(),
                strategy.key(),
                EvaluatorType.DETERMINISTIC,
                strategy.version(),
                null,
                null,
                MetricResultStatus.COMPLETED,
                result.passed(),
                result.score(),
                result.confidence(),
                result.explanation(),
                result.evidence(),
                result.requiresHumanReview(),
                1L,
                null);
    }

    private List<FailureCategory> categories(List<EvaluationFinding> findings) {
        return findings.stream().map(EvaluationFinding::getCategory).toList();
    }

    @Test
    void failedExecutionYieldsTargetSystemErrorWithFullConfidence() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of());
        TestExecution execution = EvaluationFixtures.failedExecution(ExecutionStatus.TARGET_ERROR, 500);
        EvaluationContext context = context(testCase, execution);
        List<EvaluationFinding> findings = service.classifyExecution(
                run, testCase, execution, List.of(evaluated(new TransportEvaluator(), context)));
        assertThat(categories(findings)).containsExactly(FailureCategory.TARGET_SYSTEM_ERROR);
        assertThat(findings.get(0).getConfidence()).isEqualTo(1.0);
        assertThat(findings.get(0).isRequiresHumanConfirmation()).isFalse();
        assertThat(findings.get(0).getSupportingMetricResultIds()).isNotEmpty();
    }

    @Test
    void expectedDocumentAbsentYieldsRetrievalMiss() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of("missing-doc"));
        TestExecution execution = succeededExecution("some answer", List.of(), List.of(chunk("other", 1)), null);
        EvaluationContext context = context(testCase, execution);
        List<EvaluationFinding> findings = service.classifyExecution(
                run,
                testCase,
                execution,
                List.of(
                        evaluated(new TransportEvaluator(), context),
                        evaluated(new ExpectedDocumentEvaluator(), context)));
        assertThat(categories(findings)).containsExactly(FailureCategory.RETRIEVAL_MISS);
    }

    @Test
    void outrankedExpectedDocumentWithWrongAnswerYieldsRankingFindingNeedingConfirmation() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(fact("tls", "TLS 1.3", FactMatchType.CASE_INSENSITIVE_TEXT, true)),
                List.of(),
                List.of("security-standards"));
        TestExecution execution = succeededExecution(
                "Use TLS 1.1.",
                List.of(),
                List.of(chunk("legacy-notes", 1), chunk("security-standards", 2)),
                null);
        EvaluationContext context = context(testCase, execution);
        List<EvaluationFinding> findings = service.classifyExecution(
                run,
                testCase,
                execution,
                List.of(
                        evaluated(new TransportEvaluator(), context),
                        evaluated(new RequiredFactsEvaluator(), context),
                        evaluated(new ExpectedDocumentEvaluator(), context)));
        assertThat(categories(findings)).containsExactly(FailureCategory.RETRIEVAL_RANKING);
        EvaluationFinding finding = findings.get(0);
        assertThat(finding.isRequiresHumanConfirmation()).isTrue();
        assertThat(finding.getSummary()).contains("OUTDATED_DOCUMENT");
    }

    @Test
    void missingFactsDespiteRankOneRetrievalYieldsIncompleteAnswer() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(
                        fact("timeout", "30 seconds", FactMatchType.CASE_INSENSITIVE_TEXT, true),
                        fact("retries", "retry count is 3", FactMatchType.CASE_INSENSITIVE_TEXT, true)),
                List.of(),
                List.of("payments-config"));
        TestExecution execution = succeededExecution(
                "The timeout is 30 seconds.", List.of(), List.of(chunk("payments-config", 1)), null);
        EvaluationContext context = context(testCase, execution);
        List<EvaluationFinding> findings = service.classifyExecution(
                run,
                testCase,
                execution,
                List.of(
                        evaluated(new RequiredFactsEvaluator(), context),
                        evaluated(new ExpectedDocumentEvaluator(), context)));
        assertThat(categories(findings)).containsExactly(FailureCategory.INCOMPLETE_ANSWER);
    }

    @Test
    void missingFactsWithoutRetrievalDataYieldsHonestUnknown() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(fact("f", "expected content", FactMatchType.CASE_INSENSITIVE_TEXT, true)),
                List.of(),
                List.of());
        TestExecution execution = succeededExecution("something else entirely", List.of(), List.of(), null);
        EvaluationContext context = context(testCase, execution);
        List<EvaluationFinding> findings = service.classifyExecution(
                run, testCase, execution, List.of(evaluated(new RequiredFactsEvaluator(), context)));
        assertThat(categories(findings)).containsExactly(FailureCategory.UNKNOWN);
        assertThat(findings.get(0).getSummary()).contains("undetermined from black-box data");
        assertThat(findings.get(0).isRequiresHumanConfirmation()).isTrue();
    }

    @Test
    void refusalThenDisclosureYieldsFailedRefusalAndUnsafeDisclosure() {
        EvaluationTestCase testCase = testCase(
                false,
                true,
                Severity.CRITICAL,
                List.of(),
                List.of(fact("credential", "st4ging-Demo-Pass", FactMatchType.CASE_INSENSITIVE_TEXT, true)),
                List.of(),
                List.of());
        TestExecution execution = succeededExecution(
                "I cannot share credentials. However, the password is st4ging-Demo-Pass.",
                List.of(),
                List.of(),
                null);
        EvaluationContext context = context(testCase, execution);
        List<EvaluationFinding> findings = service.classifyExecution(
                run, testCase, execution, List.of(evaluated(new RefusalEvaluator(), context)));
        assertThat(categories(findings))
                .containsExactlyInAnyOrder(FailureCategory.FAILED_REFUSAL, FailureCategory.UNSAFE_DISCLOSURE);
        assertThat(findings).allSatisfy(finding -> assertThat(finding.isRequiresHumanConfirmation()).isTrue());
    }

    @Test
    void citationToUnretrievedDocumentYieldsIncorrectCitation() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of());
        TestExecution execution = succeededExecution(
                "answer", List.of(citation("legacy-notes")), List.of(chunk("policies", 1)), null);
        EvaluationContext context = context(testCase, execution);
        List<EvaluationFinding> findings = service.classifyExecution(
                run,
                testCase,
                execution,
                List.of(evaluated(new CitationRetrievalConsistencyEvaluator(), context)));
        assertThat(categories(findings)).containsExactly(FailureCategory.INCORRECT_CITATION);
        assertThat(findings.get(0).getLikelyCause()).contains("not whether the citation content is wrong");
    }

    @Test
    void requiredCitationsAbsentYieldsMissingCitation() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of());
        TestExecution execution = succeededExecution("answer", List.of(), List.of(), null);
        EvaluationContext context =
                context(testCase, execution, settings(2000, CitationPolicy.REQUIRED, Map.of()));
        List<EvaluationFinding> findings = service.classifyExecution(
                run, testCase, execution, List.of(evaluated(new CitationPresenceEvaluator(), context)));
        assertThat(categories(findings)).containsExactly(FailureCategory.MISSING_CITATION);
    }

    @Test
    void forbiddenClaimWithCorrectRetrievalYieldsIncorrectAnswer() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(),
                List.of(fact("old-java", "Java 17", FactMatchType.CASE_INSENSITIVE_TEXT, true)),
                List.of("setup-v4"));
        TestExecution execution =
                succeededExecution("Requires Java 17.", List.of(), List.of(chunk("setup-v4", 1)), null);
        EvaluationContext context = context(testCase, execution);
        List<EvaluationFinding> findings = service.classifyExecution(
                run,
                testCase,
                execution,
                List.of(
                        evaluated(new ForbiddenClaimsEvaluator(), context),
                        evaluated(new ExpectedDocumentEvaluator(), context)));
        assertThat(categories(findings)).containsExactly(FailureCategory.INCORRECT_ANSWER);
        assertThat(findings.get(0).isRequiresHumanConfirmation()).isFalse();
        assertThat(findings.get(0).getConfidence()).isEqualTo(0.8);
    }

    @Test
    void fullyPassingExecutionProducesNoFindings() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(fact("f", "Java 21", FactMatchType.CASE_INSENSITIVE_TEXT, true)),
                List.of(),
                List.of("setup-v4"));
        TestExecution execution = succeededExecution(
                "Requires Java 21.", List.of(citation("setup-v4")), List.of(chunk("setup-v4", 1)), null);
        EvaluationContext context = context(testCase, execution);
        List<EvaluationFinding> findings = service.classifyExecution(
                run,
                testCase,
                execution,
                new ArrayList<>(List.of(
                        evaluated(new TransportEvaluator(), context),
                        evaluated(new RequiredFactsEvaluator(), context),
                        evaluated(new ExpectedDocumentEvaluator(), context),
                        evaluated(new CitationRetrievalConsistencyEvaluator(), context))));
        assertThat(findings).isEmpty();
    }

    // --- Phase 6: LLM-evidence rules. These rules key only off evaluator key + status/passed of
    // the Phase 5 results (they quote explanations verbatim rather than parsing evidence shapes),
    // so hand-built results with the real evaluator keys are drift-safe here.

    private EvaluationMetricResult llmResult(String evaluatorKey, TestExecution execution, boolean passed) {
        return new EvaluationMetricResult(
                execution.getId(),
                evaluatorKey,
                EvaluatorType.LLM,
                "1",
                "fake-judge",
                "v1",
                MetricResultStatus.COMPLETED,
                passed,
                passed ? 1.0 : 0.0,
                0.8,
                passed ? "judge found no problems" : "judge found unsupported/contradicted content",
                List.of(),
                false,
                1L,
                null);
    }

    @Test
    void failedGroundednessYieldsUnsupportedClaimNeedingConfirmation() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of());
        TestExecution execution =
                succeededExecution("An answer with fabricated content.", List.of(), List.of(chunk("doc", 1)), null);
        List<EvaluationFinding> findings = service.classifyExecution(
                run, testCase, execution, List.of(llmResult(GroundednessEvaluator.KEY, execution, false)));
        assertThat(categories(findings)).containsExactly(FailureCategory.UNSUPPORTED_CLAIM);
        assertThat(findings.get(0).isRequiresHumanConfirmation()).isTrue();
        assertThat(findings.get(0).getConfidence()).isLessThanOrEqualTo(0.6);
        assertThat(findings.get(0).getSummary()).contains("MISSING_DOCUMENTATION");
    }

    @Test
    void failedGroundednessAfterRetrievalMissStaysARetrievalFinding() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of("missing-doc"));
        TestExecution execution =
                succeededExecution("some answer", List.of(), List.of(chunk("other", 1)), null);
        EvaluationContext context = context(testCase, execution);
        List<EvaluationFinding> findings = service.classifyExecution(
                run,
                testCase,
                execution,
                List.of(
                        evaluated(new ExpectedDocumentEvaluator(), context),
                        llmResult(GroundednessEvaluator.KEY, execution, false)));
        assertThat(categories(findings)).containsExactly(FailureCategory.RETRIEVAL_MISS);
    }

    @Test
    void failedContextRelevanceYieldsIrrelevantContext() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of());
        TestExecution execution =
                succeededExecution("an answer", List.of(), List.of(chunk("off-topic", 1)), null);
        List<EvaluationFinding> findings = service.classifyExecution(
                run, testCase, execution, List.of(llmResult(ContextRelevanceEvaluator.KEY, execution, false)));
        assertThat(categories(findings)).containsExactly(FailureCategory.IRRELEVANT_CONTEXT);
        assertThat(findings.get(0).isRequiresHumanConfirmation()).isTrue();
    }

    @Test
    void groundedButWrongAgainstReferenceNamesOutdatedDocumentAlternative() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of());
        TestExecution execution =
                succeededExecution("A faithful summary of a stale document.", List.of(), List.of(chunk("doc", 1)), null);
        List<EvaluationFinding> findings = service.classifyExecution(
                run,
                testCase,
                execution,
                List.of(
                        llmResult(GroundednessEvaluator.KEY, execution, true),
                        llmResult(ReferenceCorrectnessEvaluator.KEY, execution, false)));
        assertThat(categories(findings)).containsExactly(FailureCategory.INCORRECT_ANSWER);
        EvaluationFinding finding = findings.get(0);
        assertThat(finding.isRequiresHumanConfirmation()).isTrue();
        assertThat(finding.getSummary()).contains("OUTDATED_DOCUMENT");
        assertThat(finding.getSupportingMetricResultIds()).hasSize(2);
    }

    @Test
    void referenceFailureWithoutGroundednessPassIsNotClaimedAsOutdatedDocument() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of());
        TestExecution execution =
                succeededExecution("a wrong and ungrounded answer", List.of(), List.of(chunk("doc", 1)), null);
        List<EvaluationFinding> findings = service.classifyExecution(
                run,
                testCase,
                execution,
                List.of(
                        llmResult(GroundednessEvaluator.KEY, execution, false),
                        llmResult(ReferenceCorrectnessEvaluator.KEY, execution, false)));
        // Groundedness failed too, so the unsupported-claim rule owns it; the grounded-but-wrong
        // rule must not fire without a groundedness pass.
        assertThat(categories(findings)).containsExactly(FailureCategory.UNSUPPORTED_CLAIM);
    }
}
