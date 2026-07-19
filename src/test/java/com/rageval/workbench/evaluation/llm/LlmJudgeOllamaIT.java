package com.rageval.workbench.evaluation.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.common.observability.WorkbenchMetrics;
import com.rageval.workbench.configuration.ContentLoggingProperties;
import com.rageval.workbench.configuration.LlmProperties;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.EvaluationFixtures;
import com.rageval.workbench.evaluation.application.LabelAgreementService;
import com.rageval.workbench.evaluation.application.LabelAgreementService.AgreementReport;
import com.rageval.workbench.evaluation.application.LabelAgreementService.LabelPair;
import com.rageval.workbench.evaluation.calibration.CalibrationDataset;
import com.rageval.workbench.evaluation.calibration.CalibrationDataset.CalibrationCase;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.evaluation.infrastructure.OllamaJudgeClient;
import com.rageval.workbench.run.domain.RetrievedChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;

/**
 * Real-Ollama sanity checks, run ONLY behind the {@code -Pollama-it} Maven profile — the default
 * {@code ./mvnw clean verify} never touches a real model (surefire does not pick up the
 * {@code *OllamaIT} name). Requires a running Ollama at {@code OLLAMA_BASE_URL} (default
 * localhost:11434) with {@code OLLAMA_EVALUATOR_MODEL} (default llama3.2:3b) pulled.
 *
 * <p>The calibration comparison reports agreement on the small labelled sample; it logs the
 * numbers rather than asserting a quality bar, because judge quality depends on the locally
 * chosen model and is exactly what the consultant calibrates per engagement.
 */
class LlmJudgeOllamaIT {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeOllamaIT.class);

    private static OllamaJudgeClient judge;

    @BeforeAll
    static void createJudge() {
        String baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
        String model = System.getenv().getOrDefault("OLLAMA_EVALUATOR_MODEL", "llama3.2:3b");
        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();
        judge = new OllamaJudgeClient(
                OllamaChatModel.builder().ollamaApi(api).build(),
                new LlmProperties(model, 0.0, 2),
                new WorkbenchMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new ContentLoggingProperties());
    }

    @Test
    void judgeAnswersTheStructuredSchemaForAGroundedClaim() {
        EvaluationContext context = EvaluationFixtures.context(
                EvaluationFixtures.answerableTestCase(List.of(), List.of(), List.of()),
                EvaluationFixtures.succeededExecution(
                        "The payments service requires Java 21.",
                        List.of(),
                        List.of(new RetrievedChunk(
                                "chunk-1", "payments-setup-v4", "Payments setup",
                                "The payments service requires Java 21 or newer.", 1, 0.9, Map.of())),
                        null));
        EvaluationResult result = new GroundednessEvaluator(judge).evaluate(context);

        assertThat(result.explanation()).isNotBlank();
        assertThat(result.evidence()).isNotEmpty();
        log.info("Real-model groundedness verdict: passed={} score={} explanation={}",
                result.passed(), result.score(), result.explanation());
    }

    @Test
    void calibrationSampleAgreementReportIsComputable() {
        CalibrationDataset dataset = CalibrationDataset.load();
        LabelAgreementService agreementService = new LabelAgreementService();
        List<LabelPair> pairs = new ArrayList<>();

        for (CalibrationCase calibrationCase : dataset.cases()) {
            Boolean humanRefusalLabel = calibrationCase.humanLabels().get(SemanticRefusalEvaluator.KEY);
            if (humanRefusalLabel == null) {
                continue;
            }
            EvaluationTestCase testCase = new EvaluationTestCase(
                    UUID.randomUUID(),
                    calibrationCase.id(),
                    calibrationCase.question(),
                    calibrationCase.referenceAnswer(),
                    calibrationCase.answerable(),
                    calibrationCase.shouldRefuse(),
                    Severity.MEDIUM,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    null);
            EvaluationContext context = EvaluationFixtures.context(
                    testCase,
                    EvaluationFixtures.succeededExecution(calibrationCase.answer(), List.of(), List.of(), null));
            EvaluationResult automated = new SemanticRefusalEvaluator(judge).evaluate(context);
            pairs.add(new LabelPair(
                    calibrationCase.id(),
                    SemanticRefusalEvaluator.KEY,
                    !automated.passed(),
                    !humanRefusalLabel));
        }

        AgreementReport report = agreementService.compare(pairs);
        assertThat(report.sampleSize()).isGreaterThanOrEqualTo(4);
        assertThat(report.agreementRate()).isNotNull();
        log.info("Calibration agreement (semantic refusal, {} labelled cases): rate={} tp={} fp={} tn={} fn={} — {}",
                report.sampleSize(), report.agreementRate(), report.truePositives(), report.falsePositives(),
                report.trueNegatives(), report.falseNegatives(), report.description());
    }
}
