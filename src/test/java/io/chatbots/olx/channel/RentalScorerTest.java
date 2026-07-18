package io.chatbots.olx.channel;

import io.chatbots.olx.channel.RentalScorer.Comp;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RentalScorerTest {

    private static List<BigDecimal> comparables(double... values) {
        return java.util.Arrays.stream(values).mapToObj(BigDecimal::valueOf).toList();
    }

    /** {@code n} comparables each priced at {@code total} over {@code area}, so per-m² is total/area. */
    private static List<Comp> comps(int n, double total, double area) {
        return IntStream.range(0, n)
                .mapToObj(i -> new Comp(BigDecimal.valueOf(total), BigDecimal.valueOf(area)))
                .toList();
    }

    @Test
    void noScoreBelowMinSample() {
        Optional<RentalScorer.Score> score = RentalScorer.score(
                BigDecimal.valueOf(3000), BigDecimal.valueOf(50), comps(3, 5000, 50), RentalScorer.MIN_SAMPLE);
        assertTrue(score.isEmpty());
    }

    @Test
    void respectsCallerMinSample() {
        // 6 comparables: below the default MIN_SAMPLE, but enough for the tight district+rooms bar
        assertTrue(RentalScorer.score(BigDecimal.valueOf(3000), BigDecimal.valueOf(50),
                comps(6, 5000, 50), RentalScorer.MIN_SAMPLE).isEmpty());
        assertTrue(RentalScorer.score(BigDecimal.valueOf(3000), BigDecimal.valueOf(50),
                comps(6, 5000, 50), 6).isPresent());
    }

    @Test
    void noScoreWithoutPriceOrArea() {
        List<Comp> comps = comps(20, 5000, 50);
        assertTrue(RentalScorer.score(null, BigDecimal.ONE, comps, RentalScorer.MIN_SAMPLE).isEmpty());
        assertTrue(RentalScorer.score(BigDecimal.ONE, null, comps, RentalScorer.MIN_SAMPLE).isEmpty());
        assertTrue(RentalScorer.score(BigDecimal.ONE, BigDecimal.ZERO, comps, RentalScorer.MIN_SAMPLE).isEmpty());
    }

    @Test
    void discountAgainstMedianAndMedianTotal() {
        // 12 comparables at 5000/50 = 100 per m²; offer at 80 per m² = -20%
        RentalScorer.Score score = RentalScorer.score(
                BigDecimal.valueOf(4000), BigDecimal.valueOf(50), comps(12, 5000, 50), RentalScorer.MIN_SAMPLE)
                .orElseThrow();
        assertEquals(0, score.pricePerM2().compareTo(BigDecimal.valueOf(80)));
        assertEquals(0, score.medianPerM2().compareTo(BigDecimal.valueOf(100)));
        assertEquals(0, score.medianTotal().compareTo(BigDecimal.valueOf(5000)));
        assertEquals(-20, score.diffPct());
        assertEquals(12, score.sampleSize());
    }

    @Test
    void medianTotalIsAbsolutePriceNotPerM2() {
        // 10 flats at 4000 and 10 at 6000 -> median total 5000, median per-m² 100 (both 100/m²)
        List<Comp> comps = java.util.stream.Stream.concat(
                comps(10, 4000, 40).stream(), comps(10, 6000, 60).stream()).toList();
        RentalScorer.Score s = RentalScorer.score(
                BigDecimal.valueOf(5000), BigDecimal.valueOf(50), comps, RentalScorer.MIN_SAMPLE).orElseThrow();
        assertEquals(0, s.medianTotal().compareTo(BigDecimal.valueOf(5000)));
        assertEquals(0, s.medianPerM2().compareTo(BigDecimal.valueOf(100)));
    }

    @Test
    void medianOfEvenCount() {
        assertEquals(0, RentalScorer.median(comparables(1, 2, 3, 4))
                .compareTo(BigDecimal.valueOf(2.5)));
    }

    @Test
    void medianOfOddCount() {
        assertEquals(0, RentalScorer.median(comparables(5, 1, 3))
                .compareTo(BigDecimal.valueOf(3)));
    }
}
