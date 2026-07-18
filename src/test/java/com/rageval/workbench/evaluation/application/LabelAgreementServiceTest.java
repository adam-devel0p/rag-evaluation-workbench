package com.rageval.workbench.evaluation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.evaluation.application.LabelAgreementService.AgreementReport;
import com.rageval.workbench.evaluation.application.LabelAgreementService.LabelPair;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Spec §14: automated-vs-human label agreement with honest naming — never "model accuracy". */
class LabelAgreementServiceTest {

    private final LabelAgreementService service = new LabelAgreementService();

    @Test
    void computesConfusionCountsAndAgreementRate() {
        AgreementReport report = service.compare(List.of(
                new LabelPair("CAL-01", "llm-groundedness", true, true),
                new LabelPair("CAL-02", "llm-groundedness", true, false),
                new LabelPair("CAL-03", "llm-groundedness", false, false),
                new LabelPair("CAL-04", "llm-groundedness", false, true),
                new LabelPair("CAL-05", "llm-groundedness", false, false)));

        assertThat(report.sampleSize()).isEqualTo(5);
        assertThat(report.truePositives()).isEqualTo(1);
        assertThat(report.falsePositives()).isEqualTo(1);
        assertThat(report.trueNegatives()).isEqualTo(2);
        assertThat(report.falseNegatives()).isEqualTo(1);
        assertThat(report.agreementRate()).isEqualTo(0.6);
    }

    @Test
    void emptySampleHasNullAgreementRateNeverAFakeNumber() {
        AgreementReport report = service.compare(List.of());
        assertThat(report.agreementRate()).isNull();
        assertThat(report.sampleSize()).isZero();
    }

    @Test
    void descriptionLabelsTheNumbersAsSampleAgreementNotModelAccuracy() {
        AgreementReport report = service.compare(List.of(new LabelPair("CAL-01", "llm-groundedness", true, true)));
        assertThat(report.description()).contains("calibration sample");
        assertThat(report.description()).contains("not model accuracy");
    }
}
