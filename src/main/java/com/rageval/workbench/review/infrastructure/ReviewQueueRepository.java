package com.rageval.workbench.review.infrastructure;

import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.run.domain.TestExecution;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pending-review queue (spec §20): metric results flagged {@code requiresHumanReview} that
 * have no {@code HumanReview} yet, joined with their execution and test case so the queue can be
 * filtered by run, severity, evaluator, and metric status. The query is assembled dynamically from
 * the non-null filters (a static JPQL with {@code :param is null or ...} guards trips over
 * PostgreSQL's parameter-type inference for enum/uuid parameters), ordered most-severe first, then
 * oldest first.
 */
@Repository
public class ReviewQueueRepository {

    /** One queue row: the flagged result plus the execution and test case that give it context. */
    public record PendingReviewRow(
            EvaluationMetricResult metricResult, TestExecution execution, EvaluationTestCase testCase) {
    }

    private static final String SEVERITY_PREFIX = "com.rageval.workbench.dataset.domain.Severity.";
    private static final String SEVERITY_RANK = "case"
            + " when t.severity = " + SEVERITY_PREFIX + "CRITICAL then 0"
            + " when t.severity = " + SEVERITY_PREFIX + "HIGH then 1"
            + " when t.severity = " + SEVERITY_PREFIX + "MEDIUM then 2"
            + " else 3 end";

    private final EntityManager entityManager;

    public ReviewQueueRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public Page<PendingReviewRow> findPending(
            UUID runId, Severity severity, String evaluatorKey, MetricResultStatus status, Pageable pageable) {
        StringBuilder where = new StringBuilder(
                " from EvaluationMetricResult m, TestExecution e, EvaluationTestCase t"
                        + " where m.testExecutionId = e.id and e.testCaseId = t.id"
                        + " and m.requiresHumanReview = true"
                        + " and not exists (select r from HumanReview r where r.metricResultId = m.id)");
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (runId != null) {
            where.append(" and e.evaluationRunId = :runId");
            parameters.put("runId", runId);
        }
        if (severity != null) {
            where.append(" and t.severity = :severity");
            parameters.put("severity", severity);
        }
        if (evaluatorKey != null && !evaluatorKey.isBlank()) {
            where.append(" and m.evaluatorKey = :evaluatorKey");
            parameters.put("evaluatorKey", evaluatorKey);
        }
        if (status != null) {
            where.append(" and m.status = :status");
            parameters.put("status", status);
        }

        TypedQuery<Long> countQuery =
                entityManager.createQuery("select count(m)" + where, Long.class);
        parameters.forEach(countQuery::setParameter);
        long total = countQuery.getSingleResult();

        TypedQuery<Object[]> contentQuery = entityManager.createQuery(
                "select m, e, t" + where + " order by " + SEVERITY_RANK + ", m.createdAt", Object[].class);
        parameters.forEach(contentQuery::setParameter);
        contentQuery.setFirstResult((int) pageable.getOffset());
        contentQuery.setMaxResults(pageable.getPageSize());

        List<PendingReviewRow> rows = new ArrayList<>();
        for (Object[] row : contentQuery.getResultList()) {
            rows.add(new PendingReviewRow(
                    (EvaluationMetricResult) row[0], (TestExecution) row[1], (EvaluationTestCase) row[2]));
        }
        return new PageImpl<>(rows, pageable, total);
    }

    /** Unfiltered backlog size, feeding the {@code rageval.review.backlog} gauge (spec §24). */
    @Transactional(readOnly = true)
    public long countPending() {
        return entityManager
                .createQuery(
                        "select count(m) from EvaluationMetricResult m"
                                + " where m.requiresHumanReview = true"
                                + " and not exists (select r from HumanReview r where r.metricResultId = m.id)",
                        Long.class)
                .getSingleResult();
    }
}
