package com.rageval.workbench.classification.application;

import com.rageval.workbench.evaluation.application.EvaluationService;
import com.rageval.workbench.evaluation.application.EvaluatorSettingsFactory;
import com.rageval.workbench.run.application.EvaluationPipeline;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@link EvaluationPipeline} the run worker drives, composing per-execution evaluation with
 * run-level failure classification. It lives here (not in {@code run} or {@code evaluation}) so
 * the package dependencies stay acyclic: {@code run} owns the interface, {@code classification}
 * already depends on both {@code run} and {@code evaluation} and is the natural composition point.
 *
 * <p>Failures inside the pipeline are logged and swallowed: evaluation problems are evaluator
 * problems and must never turn a completed target invocation into a failed run.
 */
@Component
public class DeterministicEvaluationPipeline implements EvaluationPipeline {

    private static final Logger log = LoggerFactory.getLogger(DeterministicEvaluationPipeline.class);

    private final EvaluationService evaluationService;
    private final FailureClassificationService classificationService;
    private final EvaluatorSettingsFactory settingsFactory;

    public DeterministicEvaluationPipeline(
            EvaluationService evaluationService,
            FailureClassificationService classificationService,
            EvaluatorSettingsFactory settingsFactory) {
        this.evaluationService = evaluationService;
        this.classificationService = classificationService;
        this.settingsFactory = settingsFactory;
    }

    @Override
    public void evaluateExecution(UUID executionId) {
        try {
            evaluationService.evaluateExecution(executionId);
        } catch (RuntimeException ex) {
            log.error(
                    "Evaluation pipeline failed for execution {} — continuing the run; the execution keeps its "
                            + "target result and can be evaluated again via resume",
                    executionId,
                    ex);
        }
    }

    @Override
    public void completeRunEvaluation(UUID runId) {
        try {
            evaluationService.evaluateMissingForRun(runId);
            classificationService.classifyRun(runId);
        } catch (RuntimeException ex) {
            log.error("Run-level evaluation completion failed for run {} — continuing finalization", runId, ex);
        }
    }

    @Override
    public Set<String> knownEvaluatorKeys() {
        return evaluationService.knownEvaluatorKeys();
    }

    @Override
    public void validateEvaluatorConfiguration(Map<String, Object> configuration) {
        settingsFactory.resolve(configuration);
    }
}
