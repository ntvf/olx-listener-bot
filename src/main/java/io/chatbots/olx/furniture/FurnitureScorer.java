package io.chatbots.olx.furniture;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Scores a used-IKEA listing against the median ask of the same model. The segment is the model
 * (a tight, SKU-like key), so a bare median of same-model whole-unit asks is a fair anchor — no
 * size normalisation is needed the way rentals need price-per-m². The shareable hook is the
 * discount, so the score is the signed % below (or above) that median.
 */
public final class FurnitureScorer {

    /** Below this many comparables the model median is noise — no deal is claimed. */
    public static final int MIN_SAMPLE = 5;

    public record Score(BigDecimal median, int diffPct, int sampleSize) {
        /** Negative diffPct means below the median; a deal is a sufficiently negative value. */
        public boolean isDealAtLeast(int discountPct) {
            return diffPct <= -Math.abs(discountPct);
        }
    }

    private FurnitureScorer() {
    }

    /**
     * @param price       this listing's ask
     * @param comparables same-model whole-unit asks from the feed's retained history
     * @param minSample   minimum comparables required for a trustworthy median
     */
    public static Optional<Score> score(BigDecimal price, List<BigDecimal> comparables, int minSample) {
        if (price == null || price.signum() <= 0) return Optional.empty();
        if (comparables == null || comparables.size() < minSample) return Optional.empty();

        BigDecimal median = median(comparables);
        if (median.signum() <= 0) return Optional.empty();

        int diffPct = price.subtract(median)
                .multiply(BigDecimal.valueOf(100))
                .divide(median, 0, RoundingMode.HALF_UP)
                .intValue();
        return Optional.of(new Score(median.setScale(0, RoundingMode.HALF_UP), diffPct, comparables.size()));
    }

    static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(BigDecimal::compareTo);
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2))
                .divide(BigDecimal.valueOf(2), MathContext.DECIMAL64);
    }
}
