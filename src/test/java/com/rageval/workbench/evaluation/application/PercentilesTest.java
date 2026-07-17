package com.rageval.workbench.evaluation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spec §11.2/§28.1: the percentile implementation must be correct, not approximate. Expected
 * values follow the linear-interpolation (R-7) definition, cross-checked against NumPy's default
 * {@code percentile}.
 */
class PercentilesTest {

    @Test
    void medianOfOddSampleIsTheMiddleValue() {
        assertThat(Percentiles.median(List.of(300L, 100L, 200L))).isEqualTo(200.0);
    }

    @Test
    void medianOfEvenSampleIsTheMeanOfTheTwoMiddleValues() {
        assertThat(Percentiles.median(List.of(100L, 200L, 300L, 400L))).isEqualTo(250.0);
    }

    @Test
    void singleElementSampleAnswersThatElementForEveryPercentile() {
        assertThat(Percentiles.percentile(List.of(42L), 0)).isEqualTo(42.0);
        assertThat(Percentiles.percentile(List.of(42L), 50)).isEqualTo(42.0);
        assertThat(Percentiles.percentile(List.of(42L), 100)).isEqualTo(42.0);
    }

    @Test
    void p90AndP95InterpolateBetweenClosestRanks() {
        // 10 values 100..1000: p90 index = 0.9*9 = 8.1 -> 900 + 0.1*100 = 910; p95 -> 955.
        List<Long> values = List.of(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L, 1000L);
        assertThat(Percentiles.percentile(values, 90)).isCloseTo(910.0, within(1e-9));
        assertThat(Percentiles.percentile(values, 95)).isCloseTo(955.0, within(1e-9));
    }

    @Test
    void percentileZeroAndHundredAreMinimumAndMaximum() {
        List<Long> values = List.of(7L, 3L, 9L, 1L);
        assertThat(Percentiles.percentile(values, 0)).isEqualTo(1.0);
        assertThat(Percentiles.percentile(values, 100)).isEqualTo(9.0);
        assertThat(Percentiles.maximum(values)).isEqualTo(9L);
    }

    @Test
    void inputOrderDoesNotMatter() {
        assertThat(Percentiles.percentile(List.of(500L, 100L, 300L, 200L, 400L), 50)).isEqualTo(300.0);
    }

    @Test
    void emptySampleAndOutOfRangePercentileAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> Percentiles.percentile(List.of(), 50));
        assertThatIllegalArgumentException().isThrownBy(() -> Percentiles.percentile(List.of(1L), -1));
        assertThatIllegalArgumentException().isThrownBy(() -> Percentiles.percentile(List.of(1L), 101));
    }
}
