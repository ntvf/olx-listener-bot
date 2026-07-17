package io.chatbots.olx.channel;

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

    @Test
    void noScoreBelowMinSample() {
        Optional<RentalScorer.Score> score = RentalScorer.score(
                BigDecimal.valueOf(3000), BigDecimal.valueOf(50), comparables(60, 70, 80));
        assertTrue(score.isEmpty());
    }

    @Test
    void noScoreWithoutPriceOrArea() {
        List<BigDecimal> comps = IntStream.range(0, 20).mapToObj(BigDecimal::valueOf).toList();
        assertTrue(RentalScorer.score(null, BigDecimal.ONE, comps).isEmpty());
        assertTrue(RentalScorer.score(BigDecimal.ONE, null, comps).isEmpty());
        assertTrue(RentalScorer.score(BigDecimal.ONE, BigDecimal.ZERO, comps).isEmpty());
    }

    @Test
    void discountAgainstMedian() {
        // 12 comparables, median 100 per m²; offer at 80 per m² = -20%
        List<BigDecimal> comps = IntStream.range(0, 12)
                .mapToObj(i -> BigDecimal.valueOf(100))
                .toList();
        RentalScorer.Score score = RentalScorer.score(
                BigDecimal.valueOf(4000), BigDecimal.valueOf(50), comps).orElseThrow();
        assertEquals(0, score.pricePerM2().compareTo(BigDecimal.valueOf(80)));
        assertEquals(0, score.medianPerM2().compareTo(BigDecimal.valueOf(100)));
        assertEquals(-20, score.diffPct());
        assertEquals(12, score.sampleSize());
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
