package com.rageval.workbench.evaluation.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.evaluation.calibration.CalibrationDataset.CalibrationCase;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Keeps the spec §14 calibration sample honest: at least 20 clearly labelled examples, every
 * required category covered, unique ids, and a human label on every case. The sample itself is
 * only judged by a real model behind the {@code -Pollama-it} profile — this test costs nothing.
 */
class CalibrationDatasetTest {

    @Test
    void calibrationSampleHasAtLeastTwentyLabelledExamplesCoveringEveryRequiredCategory() {
        CalibrationDataset dataset = CalibrationDataset.load();

        assertThat(dataset.cases()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(dataset.description()).contains("calibration sample");

        Set<String> categories = dataset.cases().stream()
                .map(CalibrationCase::category)
                .collect(Collectors.toSet());
        assertThat(categories).containsAll(CalibrationDataset.REQUIRED_CATEGORIES);

        List<String> ids = dataset.cases().stream().map(CalibrationCase::id).toList();
        assertThat(ids).doesNotHaveDuplicates();

        for (CalibrationCase calibrationCase : dataset.cases()) {
            assertThat(calibrationCase.humanLabels())
                    .as("case %s must carry at least one human label", calibrationCase.id())
                    .isNotEmpty();
            assertThat(calibrationCase.question()).isNotBlank();
            assertThat(calibrationCase.answer()).isNotBlank();
        }
    }

    @Test
    void refusalCategoriesAreLabelledOnTheSemanticRefusalMetric() {
        CalibrationDataset dataset = CalibrationDataset.load();
        dataset.cases().stream()
                .filter(calibrationCase -> calibrationCase.category().contains("refusal"))
                .forEach(calibrationCase -> assertThat(calibrationCase.humanLabels())
                        .as("case %s", calibrationCase.id())
                        .containsKey("llm-semantic-refusal"));
    }
}
