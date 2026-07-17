package com.rageval.workbench.evaluation.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Percentile calculation for run-level latency statistics (spec §11.2/§18), using linear
 * interpolation between closest ranks (the R-7 method, the default of NumPy/Excel): for a sorted
 * sample of size n, percentile p sits at index {@code (p/100) * (n-1)}, interpolating between the
 * two neighboring values when the index is fractional. The median of an even-sized sample is
 * therefore the mean of the two middle values, matching the conventional definition.
 */
public final class Percentiles {

    private Percentiles() {
    }

    /**
     * @param values the sample, in any order; must not be empty
     * @param percentile in [0, 100]
     */
    public static double percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("percentile of an empty sample is undefined");
        }
        if (percentile < 0.0 || percentile > 100.0) {
            throw new IllegalArgumentException("percentile must be within [0, 100], got " + percentile);
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double index = (percentile / 100.0) * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double fraction = index - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }

    public static double median(List<Long> values) {
        return percentile(values, 50.0);
    }

    public static long maximum(List<Long> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("maximum of an empty sample is undefined");
        }
        return Collections.max(values);
    }
}
