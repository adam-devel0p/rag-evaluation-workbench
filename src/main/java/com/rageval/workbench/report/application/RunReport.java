package com.rageval.workbench.report.application;

import com.rageval.workbench.classification.domain.FailureCategory;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.application.RunSummary;
import com.rageval.workbench.evaluation.domain.EvaluatorSettings;
import com.rageval.workbench.evaluation.domain.EvaluatorType;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.run.domain.Citation;
import com.rageval.workbench.run.domain.RetrievedChunk;
import com.rageval.workbench.run.domain.RunMode;
import com.rageval.workbench.run.domain.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The complete server-rendered reliability report model for one run (spec §22, all 20 sections).
 * Everything is computed from persisted data at render time — findings, metric results, human
 * reviews, and the current (editable) recommendation templates — so nothing here caches stale
 * conclusions. The report never blends distinct failure concepts: incorrect, unsupported,
 * incomplete, retrieval failure, and unknown root cause stay separate categories throughout.
 */
public record RunReport(
        Metadata metadata,
        Scope scope,
        List<String> limitations,
        ExecutiveSummary executiveSummary,
        RunSummary summary,
        List<ReliabilityGroup> reliabilityByQuestionType,
        List<FindingDetail> highAndCriticalFindings,
        List<CategoryGroup> failuresByCategory,
        List<CategoryGroup> retrievalFindings,
        List<CategoryGroup> refusalAndUnsupportedFindings,
        List<CategoryGroup> consistencyFindings,
        List<CategoryGroup> latencyAndErrorFindings,
        HumanReviewOverview humanReview,
        List<PrioritizedRecommendation> recommendations,
        List<AcceptanceCriterion> acceptanceCriteria,
        List<EvaluatorInfo> evaluators,
        EvaluatorSettings settings,
        List<String> enabledEvaluatorKeys) {

    /** Section 1: report title and run metadata. */
    public record Metadata(
            UUID runId,
            String runName,
            RunStatus runStatus,
            RunMode runMode,
            int repetitionsPerTestCase,
            Instant runCreatedAt,
            Instant runStartedAt,
            Instant runCompletedAt,
            String targetName,
            String targetEndpointUrl,
            String adapterType,
            String datasetName,
            String datasetVersion,
            Instant generatedAt) {
    }

    /** Section 2: what was evaluated. */
    public record Scope(
            String datasetName,
            String datasetDescription,
            long testCaseCount,
            Map<String, Long> testCasesByTag,
            Map<String, Long> testCasesBySeverity,
            int repetitionsPerTestCase,
            RunMode runMode) {
    }

    /** Section 4: the headline numbers, all backed by the summary they came from. */
    public record ExecutiveSummary(
            long totalTestCases,
            long passed,
            long failed,
            long inconclusive,
            long notEvaluated,
            Double passRate,
            long executionFailures,
            Double targetErrorRate,
            long highOrCriticalFailures,
            long totalFindings,
            long humanReviewBacklog,
            List<CategoryCount> findingsByCategory) {
    }

    public record CategoryCount(FailureCategory category, long count) {
    }

    /** Section 9: reliability by question type (answerable / refusal-expected / unanswerable). */
    public record ReliabilityGroup(
            String label, long total, long passed, long failed, long inconclusive, long notEvaluated, Double passRate) {
    }

    /**
     * One fully contextualized finding — every field spec §22 requires a major finding to show.
     * {@code observedProblem} states only what the data proves; {@code likelyCause} is null when
     * the root cause is undetermined from black-box data.
     */
    public record FindingDetail(
            UUID findingId,
            String testCaseExternalId,
            int repetitionNumber,
            FailureCategory category,
            Severity severity,
            String title,
            String observedProblem,
            String likelyCause,
            String specificRecommendation,
            String templateRecommendation,
            String acceptanceCriteria,
            Double confidence,
            boolean requiresHumanConfirmation,
            boolean humanConfirmed,
            String humanReviewStatus,
            String question,
            String expectedBehavior,
            String referenceAnswer,
            List<String> requiredFacts,
            List<String> forbiddenClaims,
            List<String> expectedDocumentIds,
            String actualResponse,
            List<Citation> citations,
            List<RetrievedChunk> retrievedChunks,
            List<MetricRow> failedMetrics,
            List<EvidenceRow> evidence) {
    }

    /** A supporting metric result, always showing the automated verdict next to any human decision. */
    public record MetricRow(
            UUID metricResultId,
            String evaluatorKey,
            MetricResultStatus status,
            Boolean passed,
            Double score,
            String explanation,
            List<String> humanDecisions) {
    }

    public record EvidenceRow(
            String type, String description, String documentId, String chunkId, Integer chunkRank, String quotedText) {
    }

    /** Findings of one category with its concept explanation — categories are never blended. */
    public record CategoryGroup(
            FailureCategory category,
            String conceptExplanation,
            long findingCount,
            List<String> affectedTestCaseIds,
            List<FindingRow> rows,
            FindingDetail representativeExample) {
    }

    public record FindingRow(
            String testCaseExternalId,
            int repetitionNumber,
            Severity severity,
            String title,
            Double confidence,
            String humanReviewStatus) {
    }

    /** Section 16: the state of the human-review workflow for this run. */
    public record HumanReviewOverview(
            long flaggedTotal,
            long pendingCount,
            long reviewedCount,
            Map<String, Long> decisionCounts,
            long findingsRequiringConfirmation,
            long findingsConfirmed,
            List<ReviewRow> reviews) {
    }

    /** One recorded decision, automated verdict and human decision side by side (spec §20). */
    public record ReviewRow(
            String testCaseExternalId,
            String evaluatorKey,
            MetricResultStatus automatedStatus,
            Boolean automatedPassed,
            Double automatedScore,
            String decision,
            Boolean overridePassed,
            Double overrideScore,
            String reviewerName,
            String comment) {
    }

    /** Section 17: per-category recommendation, ordered by severity then finding count. */
    public record PrioritizedRecommendation(
            int rank,
            FailureCategory category,
            Severity maxSeverity,
            long findingCount,
            long affectedTestCaseCount,
            String recommendedChange,
            List<String> specificRecommendations,
            List<String> exampleTestCaseIds) {
    }

    /** Section 18: what "fixed" would look like, per finding. */
    public record AcceptanceCriterion(FailureCategory category, String testCaseExternalId, String criteria) {
    }

    /** Section 20: which evaluators ran, in which version, judged by which model. */
    public record EvaluatorInfo(
            String evaluatorKey,
            EvaluatorType evaluatorType,
            String evaluatorVersion,
            String evaluatorModel,
            String promptVersion,
            long resultCount,
            long errorCount) {
    }
}
