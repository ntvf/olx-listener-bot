package io.chatbots.olx.channel;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Scores a rental against comparables from the same feed. A feed URL is already a narrow
 * search (city + segment + private-only), so a plain median of price-per-m² inside the
 * feed is a valid baseline — no model needed.
 */
public final class RentalScorer {

    /** Below this many comparables the median is noise — no score is shown. */
    public static final int MIN_SAMPLE = 10;

    public record Score(BigDecimal pricePerM2, BigDecimal medianPerM2, int diffPct, int sampleSize) {
    }

    private RentalScorer() {
    }

    /**
     * @param totalPrice       ask price plus czynsz where known
     * @param areaM2           listing area
     * @param comparablesPerM2 price-per-m² of feed offers from the comparison window
     */
    public static Optional<Score> score(BigDecimal totalPrice, BigDecimal areaM2,
                                        List<BigDecimal> comparablesPerM2) {
        if (totalPrice == null || areaM2 == null || areaM2.signum() <= 0) return Optional.empty();
        if (comparablesPerM2 == null || comparablesPerM2.size() < MIN_SAMPLE) return Optional.empty();

        BigDecimal perM2 = totalPrice.divide(areaM2, MathContext.DECIMAL64);
        BigDecimal median = median(comparablesPerM2);
        if (median.signum() <= 0) return Optional.empty();

        int diffPct = perM2.subtract(median)
                .multiply(BigDecimal.valueOf(100))
                .divide(median, 0, RoundingMode.HALF_UP)
                .intValue();
        return Optional.of(new Score(
                perM2.setScale(0, RoundingMode.HALF_UP),
                median.setScale(0, RoundingMode.HALF_UP),
                diffPct,
                comparablesPerM2.size()));
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
