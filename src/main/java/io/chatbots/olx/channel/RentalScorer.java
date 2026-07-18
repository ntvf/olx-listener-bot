package io.chatbots.olx.channel;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Scores a rental against comparables drawn from the same feed. Comparables are segmented by
 * the caller (district + room count, then coarser fallbacks), so the median reflects like-for-like
 * listings. Two medians are returned: price-per-m² (the size-normalized comparison that drives the
 * ± figure) and the absolute total price (the typical monthly cost of that district+rooms segment).
 */
public final class RentalScorer {

    /** Below this many comparables the median is noise — no score is shown. */
    public static final int MIN_SAMPLE = 10;

    /** One comparable listing: total ask (price + czynsz) and its area. */
    public record Comp(BigDecimal total, BigDecimal areaM2) {
    }

    public record Score(BigDecimal pricePerM2, BigDecimal medianPerM2, BigDecimal medianTotal,
                        int diffPct, int sampleSize) {
    }

    private RentalScorer() {
    }

    /**
     * @param totalPrice  this listing's ask price plus czynsz where known
     * @param areaM2      this listing's area
     * @param comparables segment comparables (each carries total price and area)
     * @param minSample   minimum comparables required; the caller passes a lower bar for the tight
     *                    district+rooms segment and the default {@link #MIN_SAMPLE} for wider ones
     */
    public static Optional<Score> score(BigDecimal totalPrice, BigDecimal areaM2,
                                        List<Comp> comparables, int minSample) {
        if (totalPrice == null || areaM2 == null || areaM2.signum() <= 0) return Optional.empty();
        if (comparables == null || comparables.size() < minSample) return Optional.empty();

        BigDecimal perM2 = totalPrice.divide(areaM2, MathContext.DECIMAL64);
        List<BigDecimal> perM2s = comparables.stream()
                .map(c -> c.total().divide(c.areaM2(), MathContext.DECIMAL64)).toList();
        BigDecimal medianPerM2 = median(perM2s);
        if (medianPerM2.signum() <= 0) return Optional.empty();
        BigDecimal medianTotal = median(comparables.stream().map(Comp::total).toList());

        int diffPct = perM2.subtract(medianPerM2)
                .multiply(BigDecimal.valueOf(100))
                .divide(medianPerM2, 0, RoundingMode.HALF_UP)
                .intValue();
        return Optional.of(new Score(
                perM2.setScale(0, RoundingMode.HALF_UP),
                medianPerM2.setScale(0, RoundingMode.HALF_UP),
                medianTotal.setScale(0, RoundingMode.HALF_UP),
                diffPct,
                comparables.size()));
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
