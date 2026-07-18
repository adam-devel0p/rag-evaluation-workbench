package com.rageval.workbench.report.application;

import com.rageval.workbench.classification.application.RecommendationTemplateService;
import com.rageval.workbench.classification.domain.EvaluationFinding;
import com.rageval.workbench.classification.domain.FailureCategory;
import com.rageval.workbench.classification.infrastructure.EvaluationFindingRepository;
import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.dataset.domain.EvaluationDataset;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.ExpectedFact;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.dataset.infrastructure.EvaluationDatasetRepository;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.evaluation.application.EvaluatorSettingsFactory;
import com.rageval.workbench.evaluation.application.RunSummary;
import com.rageval.workbench.evaluation.application.RunSummaryService;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.EvaluatorSettings;
import com.rageval.workbench.evaluation.domain.EvaluatorType;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.domain.TestCaseOutcome;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.evaluation.llm.AbstractLlmEvaluator;
import com.rageval.workbench.review.domain.HumanReview;
import com.rageval.workbench.review.infrastructure.HumanReviewRepository;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.RunStatus;
import com.rageval.workbench.run.domain.TestExecution;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import com.rageval.workbench.run.infrastructure.TestExecutionRepository;
import com.rageval.workbench.target.domain.TargetSystem;
import com.rageval.workbench.target.infrastructure.TargetSystemRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the {@link RunReport} for one run from persisted data only (spec §22). The report is
 * the top of the module stack: it reads run, target, dataset, evaluation, classification, and
 * review data, and nothing depends back on it. Recommendation text is read from the editable
 * per-category templates at render time — editing a template changes the next rendering without
 * touching stored findings.
 */
@Service
public class RunReportService {

    /** Categories presented as retrieval-side failures (section 12). */
    private static final Set<FailureCategory> RETRIEVAL_CATEGORIES = Set.of(
            FailureCategory.RETRIEVAL_MISS,
            FailureCategory.RETRIEVAL_RANKING,
            FailureCategory.IRRELEVANT_CONTEXT,
            FailureCategory.OUTDATED_DOCUMENT,
            FailureCategory.MISSING_DOCUMENTATION);

    /** Categories presented as refusal / unsupported-claim failures (section 13). */
    private static final Set<FailureCategory> REFUSAL_CATEGORIES = Set.of(
            FailureCategory.FAILED_REFUSAL,
            FailureCategory.UNSAFE_DISCLOSURE,
            FailureCategory.ACCESS_CONTROL_RISK,
            FailureCategory.UNSUPPORTED_CLAIM);

    private static final Set<FailureCategory> CONSISTENCY_CATEGORIES =
            Set.of(FailureCategory.INCONSISTENT_RESPONSE);

    private static final Set<FailureCategory> LATENCY_ERROR_CATEGORIES =
            Set.of(FailureCategory.HIGH_LATENCY, FailureCategory.TARGET_SYSTEM_ERROR);

    /**
     * What each failure category means — the report keeps incorrect, unsupported, incomplete,
     * retrieval failure, and unknown root cause as separate concepts (spec §22).
     */
    private static final Map<FailureCategory, String> CONCEPT_EXPLANATIONS = new EnumMap<>(Map.ofEntries(
            Map.entry(FailureCategory.TARGET_SYSTEM_ERROR,
                    "The target system failed at the transport level (error, timeout, or unusable response). Answer"
                            + " quality was not judged — there was no answer to judge."),
            Map.entry(FailureCategory.MISSING_DOCUMENTATION,
                    "The expected information appears to exist in no source document. Requires human confirmation —"
                            + " black-box data cannot prove a document's absence."),
            Map.entry(FailureCategory.OUTDATED_DOCUMENT,
                    "A superseded document version influenced the answer. Usually surfaces as the alternative"
                            + " explanation of a ranking or grounded-but-wrong finding; a human must confirm it."),
            Map.entry(FailureCategory.RETRIEVAL_MISS,
                    "Retrieval failure: none of the expected documents appear in the retrieved chunks. The answer"
                            + " never had the right material to work from."),
            Map.entry(FailureCategory.RETRIEVAL_RANKING,
                    "Retrieval failure: an expected document was retrieved but ranked below competing chunks, and"
                            + " the answer is wrong — it plausibly followed the higher-ranked material."),
            Map.entry(FailureCategory.IRRELEVANT_CONTEXT,
                    "Retrieval failure: the retrieved chunks are predominantly irrelevant to the question,"
                            + " distracting or starving the generation step."),
            Map.entry(FailureCategory.UNSUPPORTED_CLAIM,
                    "Unsupported answer: the answer states content no retrieved chunk backs — possibly fabricated."
                            + " Distinct from an incorrect answer: an unsupported claim may even be true, but the"
                            + " system cannot substantiate it."),
            Map.entry(FailureCategory.INCORRECT_ANSWER,
                    "Incorrect answer: the answer contradicts known-correct facts or its own retrieved context."
                            + " Distinct from unsupported (unverifiable) and incomplete (missing content)."),
            Map.entry(FailureCategory.INCOMPLETE_ANSWER,
                    "Incomplete answer: mandatory facts are missing although the right material was retrieved —"
                            + " the generation step dropped available content."),
            Map.entry(FailureCategory.INCORRECT_CITATION,
                    "The answer cites a document that was not in the retrieved context — the citation cannot come"
                            + " from what the system actually read."),
            Map.entry(FailureCategory.MISSING_CITATION,
                    "Citations are required for this test case but the answer carries none."),
            Map.entry(FailureCategory.FAILED_REFUSAL,
                    "A refusal was required but the system answered, or refused and then kept talking."),
            Map.entry(FailureCategory.UNSAFE_DISCLOSURE,
                    "Content the test case marks as forbidden appeared in an answer that should have been a"
                            + " refusal — a safety-relevant disclosure."),
            Map.entry(FailureCategory.ACCESS_CONTROL_RISK,
                    "The system disclosed content the requesting user context should not be able to reach."),
            Map.entry(FailureCategory.INCONSISTENT_RESPONSE,
                    "Repetitions of the same question produced materially different facts, citations, or refusal"
                            + " behavior. Wording variation is not counted."),
            Map.entry(FailureCategory.HIGH_LATENCY,
                    "The answer exceeded the configured maximum acceptable latency."),
            Map.entry(FailureCategory.EVALUATOR_DISAGREEMENT,
                    "Two evaluators judging the same aspect disagreed — an evaluation-tooling signal routed to"
                            + " human review, not a verdict about the target."),
            Map.entry(FailureCategory.UNKNOWN,
                    "Unknown root cause: a metric failed but no classification rule could establish a cause from"
                            + " black-box data. Observed symptom only — never forced into a guessed category.")));

    private final EvaluationRunRepository runRepository;
    private final TargetSystemRepository targetRepository;
    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationTestCaseRepository testCaseRepository;
    private final TestExecutionRepository executionRepository;
    private final EvaluationMetricResultRepository metricResultRepository;
    private final EvaluationFindingRepository findingRepository;
    private final HumanReviewRepository reviewRepository;
    private final RunSummaryService summaryService;
    private final EvaluatorSettingsFactory settingsFactory;
    private final RecommendationTemplateService templateService;

    public RunReportService(
            EvaluationRunRepository runRepository,
            TargetSystemRepository targetRepository,
            EvaluationDatasetRepository datasetRepository,
            EvaluationTestCaseRepository testCaseRepository,
            TestExecutionRepository executionRepository,
            EvaluationMetricResultRepository metricResultRepository,
            EvaluationFindingRepository findingRepository,
            HumanReviewRepository reviewRepository,
            RunSummaryService summaryService,
            EvaluatorSettingsFactory settingsFactory,
            RecommendationTemplateService templateService) {
        this.runRepository = runRepository;
        this.targetRepository = targetRepository;
        this.datasetRepository = datasetRepository;
        this.testCaseRepository = testCaseRepository;
        this.executionRepository = executionRepository;
        this.metricResultRepository = metricResultRepository;
        this.findingRepository = findingRepository;
        this.reviewRepository = reviewRepository;
        this.summaryService = summaryService;
        this.settingsFactory = settingsFactory;
        this.templateService = templateService;
    }

    @Transactional(readOnly = true)
    public RunReport build(UUID runId) {
        EvaluationRun run = runRepository
                .findById(runId)
                .orElseThrow(() -> new NotFoundException("Evaluation run not found: " + runId));
        TargetSystem target = targetRepository.findById(run.getTargetSystemId()).orElse(null);
        EvaluationDataset dataset = datasetRepository.findById(run.getDatasetId()).orElse(null);
        Map<UUID, EvaluationTestCase> testCases = testCaseRepository.findByDatasetId(run.getDatasetId()).stream()
                .collect(Collectors.toMap(EvaluationTestCase::getId, testCase -> testCase));
        Map<UUID, TestExecution> executions = executionRepository.findByEvaluationRunId(runId).stream()
                .collect(Collectors.toMap(TestExecution::getId, execution -> execution));
        List<EvaluationMetricResult> results = metricResultRepository.findByRunId(runId);
        Map<UUID, EvaluationMetricResult> resultsById =
                results.stream().collect(Collectors.toMap(EvaluationMetricResult::getId, result -> result));
        List<EvaluationFinding> findings = findingRepository.findByEvaluationRunId(runId);
        Map<UUID, List<HumanReview>> reviewsByMetricResult = results.isEmpty()
                ? Map.of()
                : reviewRepository
                        .findByMetricResultIdIn(
                                results.stream().map(EvaluationMetricResult::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(HumanReview::getMetricResultId));
        RunSummary summary = summaryService.summarize(runId);
        EvaluatorSettings settings = settingsFactory.resolve(run);
        Map<FailureCategory, String> templates = templateService.currentTexts();

        List<EvaluationFinding> sortedFindings = findings.stream()
                .sorted(Comparator.comparing((EvaluationFinding finding) -> finding.getSeverity().ordinal())
                        .reversed()
                        .thenComparing(finding -> finding.getCategory().name())
                        .thenComparing(EvaluationFinding::getCreatedAt))
                .toList();
        Map<FailureCategory, List<EvaluationFinding>> findingsByCategory = sortedFindings.stream()
                .collect(Collectors.groupingBy(
                        EvaluationFinding::getCategory, () -> new EnumMap<>(FailureCategory.class),
                        Collectors.toList()));

        List<RunReport.FindingDetail> highCritical = sortedFindings.stream()
                .filter(finding -> finding.getSeverity() == Severity.HIGH || finding.getSeverity() == Severity.CRITICAL)
                .map(finding -> findingDetail(
                        finding, executions, testCases, resultsById, reviewsByMetricResult, templates))
                .toList();

        List<RunReport.CategoryGroup> allGroups = findingsByCategory.entrySet().stream()
                .map(entry -> categoryGroup(
                        entry.getKey(), entry.getValue(), executions, testCases, resultsById,
                        reviewsByMetricResult, templates))
                .toList();

        return new RunReport(
                metadata(run, target, dataset),
                scope(dataset, testCases.values(), run),
                limitations(run, summary, results),
                executiveSummary(summary, findings),
                summary,
                reliabilityByQuestionType(summary, testCases),
                highCritical,
                allGroups,
                filterGroups(allGroups, RETRIEVAL_CATEGORIES),
                filterGroups(allGroups, REFUSAL_CATEGORIES),
                filterGroups(allGroups, CONSISTENCY_CATEGORIES),
                filterGroups(allGroups, LATENCY_ERROR_CATEGORIES),
                humanReviewOverview(results, findings, reviewsByMetricResult, executions, testCases),
                recommendations(findingsByCategory, executions, testCases, templates),
                acceptanceCriteria(sortedFindings, executions, testCases),
                evaluatorInfos(results),
                settings,
                run.getEnabledEvaluatorKeys());
    }

    private RunReport.Metadata metadata(EvaluationRun run, TargetSystem target, EvaluationDataset dataset) {
        return new RunReport.Metadata(
                run.getId(),
                run.getName(),
                run.getStatus(),
                run.getRunMode(),
                run.getRepetitionsPerTestCase(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getCompletedAt(),
                target != null ? target.getName() : "(deleted target)",
                target != null ? target.getEndpointUrl() : null,
                target != null ? target.getAdapterType().name() : null,
                dataset != null ? dataset.getName() : "(deleted dataset)",
                dataset != null ? dataset.getDatasetVersion() : null,
                Instant.now());
    }

    private RunReport.Scope scope(
            EvaluationDataset dataset, java.util.Collection<EvaluationTestCase> testCases, EvaluationRun run) {
        Map<String, Long> byTag = new TreeMap<>();
        Map<String, Long> bySeverity = new TreeMap<>();
        for (EvaluationTestCase testCase : testCases) {
            testCase.getTags().forEach(tag -> byTag.merge(tag, 1L, Long::sum));
            bySeverity.merge(testCase.getSeverity().name(), 1L, Long::sum);
        }
        return new RunReport.Scope(
                dataset != null ? dataset.getName() : "(deleted dataset)",
                dataset != null ? dataset.getDescription() : null,
                testCases.size(),
                byTag,
                bySeverity,
                run.getRepetitionsPerTestCase(),
                run.getRunMode());
    }

    /** Section 3: honest limitations — the fixed black-box caveats plus what this run actually hit. */
    private List<String> limitations(EvaluationRun run, RunSummary summary, List<EvaluationMetricResult> results) {
        List<String> limitations = new ArrayList<>();
        limitations.add("Black-box evaluation: only the target's observable HTTP behavior was assessed. Internal"
                + " retrieval indexes, prompts, or model versions were not inspected; where the data proves only a"
                + " symptom, findings say so instead of claiming a root cause.");
        limitations.add("A grounded answer can still be wrong if its source document is outdated — groundedness and"
                + " reference correctness are separate metrics and are never merged into one number.");
        limitations.add("Findings marked \"requires human confirmation\" are evidence-backed hypotheses, not"
                + " conclusions, until a reviewer confirms them.");
        long evaluatorErrors = results.stream()
                .filter(result -> result.getStatus() == MetricResultStatus.FAILED)
                .count();
        if (evaluatorErrors > 0) {
            limitations.add(evaluatorErrors + " metric result(s) are evaluator failures (e.g. the local judge model"
                    + " was unavailable). These are tool failures, never chatbot failures: they are excluded from"
                    + " every pass-rate denominator and the affected metrics are marked as not assessed.");
        }
        boolean llmJudged = results.stream().anyMatch(result -> result.getEvaluatorType() == EvaluatorType.LLM);
        if (llmJudged) {
            limitations.add("LLM-judged metrics are themselves model outputs with known error modes. Low-confidence"
                    + " verdicts, cross-evaluator disagreements, and malformed judge output are flagged for human"
                    + " review rather than trusted; agreement is calibrated against a small labelled sample, which"
                    + " is not a model-accuracy guarantee.");
        }
        long truncated = results.stream()
                .filter(result -> result.getEvidence().stream()
                        .anyMatch(evidence -> AbstractLlmEvaluator.CONTEXT_TRUNCATION_EVIDENCE.equals(evidence.type())))
                .count();
        if (truncated > 0) {
            limitations.add(truncated + " LLM evaluation(s) judged retrieved context truncated to the configured"
                    + " cap; their verdicts may miss evidence beyond the cap and are flagged for review.");
        }
        if (run.getStatus() != RunStatus.COMPLETED) {
            limitations.add("The run ended with status " + run.getStatus()
                    + " — results cover only the executions that actually ran.");
        }
        if (summary.executions().incomplete() > 0 || summary.executions().cancelled() > 0) {
            limitations.add((summary.executions().incomplete() + summary.executions().cancelled())
                    + " execution(s) were cancelled or never ran and produced no evidence.");
        }
        return limitations;
    }

    private RunReport.ExecutiveSummary executiveSummary(RunSummary summary, List<EvaluationFinding> findings) {
        Map<FailureCategory, Long> counts = findings.stream()
                .collect(Collectors.groupingBy(
                        EvaluationFinding::getCategory, () -> new EnumMap<>(FailureCategory.class),
                        Collectors.counting()));
        List<RunReport.CategoryCount> byCategory = counts.entrySet().stream()
                .sorted(Map.Entry.<FailureCategory, Long>comparingByValue().reversed())
                .map(entry -> new RunReport.CategoryCount(entry.getKey(), entry.getValue()))
                .toList();
        return new RunReport.ExecutiveSummary(
                summary.testCaseOutcomes().total(),
                summary.testCaseOutcomes().passed(),
                summary.testCaseOutcomes().failed(),
                summary.testCaseOutcomes().inconclusive(),
                summary.testCaseOutcomes().notEvaluated(),
                summary.testCaseOutcomes().passRate(),
                summary.executions().failed(),
                summary.targetErrorRate(),
                summary.highOrCriticalFailureCount(),
                findings.size(),
                summary.humanReviewBacklog(),
                byCategory);
    }

    /** Section 9: outcome distribution per question type — the reliability axis tags don't cover. */
    private List<RunReport.ReliabilityGroup> reliabilityByQuestionType(
            RunSummary summary, Map<UUID, EvaluationTestCase> testCases) {
        Map<String, List<RunSummary.TestCaseSummary>> groups = new LinkedHashMap<>();
        groups.put("Answerable questions", new ArrayList<>());
        groups.put("Refusal-expected questions", new ArrayList<>());
        groups.put("Unanswerable questions", new ArrayList<>());
        for (RunSummary.TestCaseSummary testCaseSummary : summary.testCases()) {
            EvaluationTestCase testCase = testCases.get(testCaseSummary.testCaseId());
            if (testCase == null) {
                continue;
            }
            String key = testCase.isShouldRefuse()
                    ? "Refusal-expected questions"
                    : testCase.isAnswerable() ? "Answerable questions" : "Unanswerable questions";
            groups.get(key).add(testCaseSummary);
        }
        List<RunReport.ReliabilityGroup> reliability = new ArrayList<>();
        groups.forEach((label, members) -> {
            long passed = members.stream().filter(m -> m.outcome() == TestCaseOutcome.PASSED).count();
            long failed = members.stream().filter(m -> m.outcome() == TestCaseOutcome.FAILED).count();
            long inconclusive = members.stream().filter(m -> m.outcome() == TestCaseOutcome.INCONCLUSIVE).count();
            long notEvaluated = members.stream().filter(m -> m.outcome() == TestCaseOutcome.NOT_EVALUATED).count();
            reliability.add(new RunReport.ReliabilityGroup(
                    label,
                    members.size(),
                    passed,
                    failed,
                    inconclusive,
                    notEvaluated,
                    passed + failed > 0 ? (double) passed / (passed + failed) : null));
        });
        return reliability;
    }

    private RunReport.CategoryGroup categoryGroup(
            FailureCategory category,
            List<EvaluationFinding> findings,
            Map<UUID, TestExecution> executions,
            Map<UUID, EvaluationTestCase> testCases,
            Map<UUID, EvaluationMetricResult> resultsById,
            Map<UUID, List<HumanReview>> reviewsByMetricResult,
            Map<FailureCategory, String> templates) {
        List<RunReport.FindingRow> rows = findings.stream()
                .map(finding -> new RunReport.FindingRow(
                        externalId(finding, executions, testCases),
                        repetition(finding, executions),
                        finding.getSeverity(),
                        finding.getTitle(),
                        finding.getConfidence(),
                        humanReviewStatus(finding, reviewsByMetricResult)))
                .toList();
        List<String> affected = findings.stream()
                .map(finding -> externalId(finding, executions, testCases))
                .distinct()
                .sorted()
                .toList();
        RunReport.FindingDetail example = findings.isEmpty()
                ? null
                : findingDetail(findings.get(0), executions, testCases, resultsById, reviewsByMetricResult, templates);
        return new RunReport.CategoryGroup(
                category,
                CONCEPT_EXPLANATIONS.getOrDefault(category, category.name()),
                findings.size(),
                affected,
                rows,
                example);
    }

    private static List<RunReport.CategoryGroup> filterGroups(
            List<RunReport.CategoryGroup> groups, Set<FailureCategory> categories) {
        return groups.stream().filter(group -> categories.contains(group.category())).toList();
    }

    private RunReport.FindingDetail findingDetail(
            EvaluationFinding finding,
            Map<UUID, TestExecution> executions,
            Map<UUID, EvaluationTestCase> testCases,
            Map<UUID, EvaluationMetricResult> resultsById,
            Map<UUID, List<HumanReview>> reviewsByMetricResult,
            Map<FailureCategory, String> templates) {
        TestExecution execution = executions.get(finding.getTestExecutionId());
        EvaluationTestCase testCase =
                execution != null ? testCases.get(execution.getTestCaseId()) : null;
        List<RunReport.MetricRow> metricRows = finding.getSupportingMetricResultIds().stream()
                .map(resultsById::get)
                .filter(result -> result != null)
                .map(result -> new RunReport.MetricRow(
                        result.getId(),
                        result.getEvaluatorKey(),
                        result.getStatus(),
                        result.getPassed(),
                        result.getScore(),
                        result.getExplanation(),
                        decisionsOf(reviewsByMetricResult.get(result.getId()))))
                .toList();
        List<RunReport.EvidenceRow> evidence = finding.getEvidence().stream()
                .map(item -> new RunReport.EvidenceRow(
                        item.type(),
                        item.description(),
                        item.documentId(),
                        item.chunkId(),
                        item.chunkRank(),
                        item.quotedText()))
                .toList();
        return new RunReport.FindingDetail(
                finding.getId(),
                externalId(finding, executions, testCases),
                repetition(finding, executions),
                finding.getCategory(),
                finding.getSeverity(),
                finding.getTitle(),
                finding.getSummary(),
                finding.getLikelyCause(),
                finding.getRecommendation(),
                templates.get(finding.getCategory()),
                finding.getAcceptanceCriteria(),
                finding.getConfidence(),
                finding.isRequiresHumanConfirmation(),
                finding.isHumanConfirmed(),
                humanReviewStatus(finding, reviewsByMetricResult),
                testCase != null ? testCase.getQuestion() : null,
                expectedBehavior(testCase),
                testCase != null ? testCase.getReferenceAnswer() : null,
                factsDisplay(testCase != null ? testCase.getRequiredFacts() : List.of()),
                factsDisplay(testCase != null ? testCase.getForbiddenClaims() : List.of()),
                testCase != null ? testCase.getExpectedDocumentIds() : List.of(),
                actualResponse(execution),
                execution != null && execution.getCitations() != null ? execution.getCitations() : List.of(),
                execution != null && execution.getRetrievedChunks() != null
                        ? execution.getRetrievedChunks()
                        : List.of(),
                metricRows,
                evidence);
    }

    private static String expectedBehavior(EvaluationTestCase testCase) {
        if (testCase == null) {
            return null;
        }
        if (testCase.isShouldRefuse()) {
            return "Refuse to answer and disclose nothing sensitive.";
        }
        if (!testCase.isAnswerable()) {
            return "State that the information is not available instead of fabricating an answer.";
        }
        StringBuilder expected = new StringBuilder("Answer the question");
        if (!testCase.getRequiredFacts().isEmpty()) {
            expected.append(", stating the required facts listed below");
        }
        if (!testCase.getExpectedDocumentIds().isEmpty()) {
            expected.append(", based on document(s) ").append(String.join(", ", testCase.getExpectedDocumentIds()));
        }
        return expected.append(".").toString();
    }

    private static String actualResponse(TestExecution execution) {
        if (execution == null) {
            return null;
        }
        if (execution.getAnswer() != null && !execution.getAnswer().isBlank()) {
            return execution.getAnswer();
        }
        StringBuilder description = new StringBuilder("(no answer — execution ")
                .append(execution.getStatus());
        if (execution.getHttpStatus() != null) {
            description.append(", HTTP ").append(execution.getHttpStatus());
        }
        if (execution.getErrorMessage() != null) {
            description.append(": ").append(execution.getErrorMessage());
        }
        return description.append(")").toString();
    }

    private static List<String> factsDisplay(List<ExpectedFact> facts) {
        return facts.stream()
                .map(fact -> fact.value() + " [" + fact.matchType() + (fact.mandatory() ? ", mandatory" : "") + "]")
                .toList();
    }

    private String humanReviewStatus(
            EvaluationFinding finding, Map<UUID, List<HumanReview>> reviewsByMetricResult) {
        List<String> decisions = finding.getSupportingMetricResultIds().stream()
                .flatMap(id -> reviewsByMetricResult.getOrDefault(id, List.of()).stream())
                .map(review -> review.getDecision().name())
                .distinct()
                .toList();
        if (finding.isHumanConfirmed()) {
            return "Confirmed by human review";
        }
        if (!decisions.isEmpty()) {
            return "Reviewed (" + String.join(", ", decisions) + ")"
                    + (finding.isRequiresHumanConfirmation() ? ", not confirmed" : "");
        }
        return finding.isRequiresHumanConfirmation() ? "Awaiting human confirmation" : "No human review required";
    }

    private static List<String> decisionsOf(List<HumanReview> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return List.of();
        }
        return reviews.stream()
                .sorted(Comparator.comparing(HumanReview::getCreatedAt))
                .map(review -> review.getDecision().name() + " by " + review.getReviewerName()
                        + (review.getOverridePassed() != null
                                ? " (override passed=" + review.getOverridePassed()
                                        + (review.getOverrideScore() != null
                                                ? ", score=" + review.getOverrideScore()
                                                : "")
                                        + ")"
                                : ""))
                .toList();
    }

    private RunReport.HumanReviewOverview humanReviewOverview(
            List<EvaluationMetricResult> results,
            List<EvaluationFinding> findings,
            Map<UUID, List<HumanReview>> reviewsByMetricResult,
            Map<UUID, TestExecution> executions,
            Map<UUID, EvaluationTestCase> testCases) {
        long flagged = results.stream().filter(EvaluationMetricResult::isRequiresHumanReview).count();
        long pending = results.stream()
                .filter(EvaluationMetricResult::isRequiresHumanReview)
                .filter(result -> !reviewsByMetricResult.containsKey(result.getId()))
                .count();
        Map<String, Long> decisionCounts = new TreeMap<>();
        List<RunReport.ReviewRow> rows = new ArrayList<>();
        for (EvaluationMetricResult result : results) {
            List<HumanReview> reviews = reviewsByMetricResult.get(result.getId());
            if (reviews == null) {
                continue;
            }
            TestExecution execution = executions.get(result.getTestExecutionId());
            EvaluationTestCase testCase =
                    execution != null ? testCases.get(execution.getTestCaseId()) : null;
            for (HumanReview review : reviews) {
                decisionCounts.merge(review.getDecision().name(), 1L, Long::sum);
                rows.add(new RunReport.ReviewRow(
                        testCase != null ? testCase.getExternalId() : "?",
                        result.getEvaluatorKey(),
                        result.getStatus(),
                        result.getPassed(),
                        result.getScore(),
                        review.getDecision().name(),
                        review.getOverridePassed(),
                        review.getOverrideScore(),
                        review.getReviewerName(),
                        review.getComment()));
            }
        }
        rows.sort(Comparator.comparing(RunReport.ReviewRow::testCaseExternalId)
                .thenComparing(RunReport.ReviewRow::evaluatorKey));
        return new RunReport.HumanReviewOverview(
                flagged,
                pending,
                reviewsByMetricResult.size(),
                decisionCounts,
                findings.stream().filter(EvaluationFinding::isRequiresHumanConfirmation).count(),
                findings.stream().filter(EvaluationFinding::isHumanConfirmed).count(),
                rows);
    }

    /** Section 17: recommendations from the editable per-category templates, most severe and most frequent first. */
    private List<RunReport.PrioritizedRecommendation> recommendations(
            Map<FailureCategory, List<EvaluationFinding>> findingsByCategory,
            Map<UUID, TestExecution> executions,
            Map<UUID, EvaluationTestCase> testCases,
            Map<FailureCategory, String> templates) {
        record Priority(FailureCategory category, Severity maxSeverity, List<EvaluationFinding> findings) {
        }
        List<Priority> priorities = findingsByCategory.entrySet().stream()
                .map(entry -> new Priority(
                        entry.getKey(),
                        entry.getValue().stream()
                                .map(EvaluationFinding::getSeverity)
                                .max(Comparator.comparingInt(Severity::ordinal))
                                .orElse(Severity.LOW),
                        entry.getValue()))
                .sorted(Comparator.comparing((Priority priority) -> priority.maxSeverity().ordinal())
                        .reversed()
                        .thenComparing(Comparator.comparingInt((Priority priority) ->
                                        priority.findings().size())
                                .reversed()))
                .toList();
        List<RunReport.PrioritizedRecommendation> recommendations = new ArrayList<>();
        int rank = 1;
        for (Priority priority : priorities) {
            List<String> affected = priority.findings().stream()
                    .map(finding -> externalId(finding, executions, testCases))
                    .distinct()
                    .sorted()
                    .toList();
            List<String> specific = priority.findings().stream()
                    .map(EvaluationFinding::getRecommendation)
                    .filter(recommendation -> recommendation != null && !recommendation.isBlank())
                    .distinct()
                    .limit(3)
                    .toList();
            recommendations.add(new RunReport.PrioritizedRecommendation(
                    rank++,
                    priority.category(),
                    priority.maxSeverity(),
                    priority.findings().size(),
                    affected.size(),
                    templates.getOrDefault(
                            priority.category(),
                            "No template configured for " + priority.category()
                                    + " — see the per-finding recommendations."),
                    specific,
                    affected.stream().limit(5).toList()));
        }
        return recommendations;
    }

    private List<RunReport.AcceptanceCriterion> acceptanceCriteria(
            List<EvaluationFinding> findings,
            Map<UUID, TestExecution> executions,
            Map<UUID, EvaluationTestCase> testCases) {
        return findings.stream()
                .filter(finding -> finding.getAcceptanceCriteria() != null
                        && !finding.getAcceptanceCriteria().isBlank())
                .map(finding -> new RunReport.AcceptanceCriterion(
                        finding.getCategory(),
                        externalId(finding, executions, testCases),
                        finding.getAcceptanceCriteria()))
                .distinct()
                .toList();
    }

    private List<RunReport.EvaluatorInfo> evaluatorInfos(List<EvaluationMetricResult> results) {
        Map<String, List<EvaluationMetricResult>> byKey = new TreeMap<>(
                results.stream().collect(Collectors.groupingBy(EvaluationMetricResult::getEvaluatorKey)));
        List<RunReport.EvaluatorInfo> infos = new ArrayList<>();
        byKey.forEach((key, keyResults) -> {
            EvaluationMetricResult sample = keyResults.get(0);
            infos.add(new RunReport.EvaluatorInfo(
                    key,
                    sample.getEvaluatorType(),
                    sample.getEvaluatorVersion(),
                    keyResults.stream()
                            .map(EvaluationMetricResult::getEvaluatorModel)
                            .filter(model -> model != null)
                            .findFirst()
                            .orElse(null),
                    sample.getPromptVersion(),
                    keyResults.size(),
                    keyResults.stream()
                            .filter(result -> result.getStatus() == MetricResultStatus.FAILED)
                            .count()));
        });
        return infos;
    }

    private String externalId(
            EvaluationFinding finding,
            Map<UUID, TestExecution> executions,
            Map<UUID, EvaluationTestCase> testCases) {
        TestExecution execution = executions.get(finding.getTestExecutionId());
        EvaluationTestCase testCase = execution != null ? testCases.get(execution.getTestCaseId()) : null;
        return testCase != null ? testCase.getExternalId() : finding.getTestExecutionId().toString();
    }

    private int repetition(EvaluationFinding finding, Map<UUID, TestExecution> executions) {
        TestExecution execution = executions.get(finding.getTestExecutionId());
        return execution != null ? execution.getRepetitionNumber() : 0;
    }
}
