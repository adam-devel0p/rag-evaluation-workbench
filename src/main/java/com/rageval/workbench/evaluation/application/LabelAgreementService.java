package com.rageval.workbench.evaluation.application;

import java.util.Collection;
import org.springframework.stereotype.Service;

/**
 * Compares automated evaluator labels with human labels over a labelled sample (spec §14). A
 * "positive" is a flagged defect: the evaluator judging the case as failed. The output is an
 * agreement report over the given sample — deliberately never called "model accuracy": the
 * calibration set is a small hand-labelled sample, and these numbers describe agreement on that
 * sample only.
 */
@Service
public class LabelAgreementService {

    /**
     * One case's pair of labels for one metric: what the automated evaluator decided and what a
     * human decided. {@code failed} means a defect was flagged (the positive class).
     */
    public record LabelPair(String caseId, String metricKey, boolean automatedFailed, boolean humanFailed) {
    }

    /**
     * Agreement between automated and human labels on a labelled sample. Positive class = defect
     * flagged (failed). {@code agreementRate} is (truePositives + trueNegatives) / sampleSize,
     * null on an empty sample — never a fake number.
     */
    public record AgreementReport(
            int sampleSize,
            long truePositives,
            long falsePositives,
            long trueNegatives,
            long falseNegatives,
            Double agreementRate,
            String description) {
    }

    public AgreementReport compare(Collection<LabelPair> pairs) {
        long truePositives = pairs.stream().filter(p -> p.automatedFailed() && p.humanFailed()).count();
        long falsePositives = pairs.stream().filter(p -> p.automatedFailed() && !p.humanFailed()).count();
        long trueNegatives = pairs.stream().filter(p -> !p.automatedFailed() && !p.humanFailed()).count();
        long falseNegatives = pairs.stream().filter(p -> !p.automatedFailed() && p.humanFailed()).count();
        int sampleSize = pairs.size();
        Double agreementRate = sampleSize > 0 ? (double) (truePositives + trueNegatives) / sampleSize : null;
        return new AgreementReport(
                sampleSize,
                truePositives,
                falsePositives,
                trueNegatives,
                falseNegatives,
                agreementRate,
                "Agreement between automated evaluator labels and human labels on a small labelled "
                        + "calibration sample of " + sampleSize + " case-metric pair(s). Positive class = "
                        + "defect flagged. This is sample agreement, not model accuracy.");
    }
}
