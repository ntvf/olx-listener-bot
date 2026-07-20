package io.chatbots.olx.furniture;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FurnitureScorerTest {

    private static List<BigDecimal> prices(double... values) {
        return java.util.Arrays.stream(values).mapToObj(BigDecimal::valueOf).toList();
    }

    @Test
    void discountBelowMedianIsANegativeDeal() {
        Optional<FurnitureScorer.Score> s = FurnitureScorer.score(
                BigDecimal.valueOf(120), prices(400, 420, 450, 500, 600), FurnitureScorer.MIN_SAMPLE);
        assertTrue(s.isPresent());
        assertEquals(450, s.get().median().intValue());
        assertEquals(-73, s.get().diffPct()); // (120-450)/450 = -73%
        assertEquals(5, s.get().sampleSize());
        assertTrue(s.get().isDealAtLeast(25));
    }

    @Test
    void aboveMedianIsNotADeal() {
        Optional<FurnitureScorer.Score> s = FurnitureScorer.score(
                BigDecimal.valueOf(500), prices(400, 400, 400, 400, 400), FurnitureScorer.MIN_SAMPLE);
        assertTrue(s.isPresent());
        assertEquals(25, s.get().diffPct());
        assertFalse(s.get().isDealAtLeast(25));
    }

    @Test
    void tooFewComparablesYieldsNoScore() {
        assertTrue(FurnitureScorer.score(BigDecimal.valueOf(120), prices(400, 420, 450, 500),
                FurnitureScorer.MIN_SAMPLE).isEmpty());
    }

    @Test
    void nullOrNonPositivePriceYieldsNoScore() {
        assertTrue(FurnitureScorer.score(null, prices(400, 420, 450, 500, 600),
                FurnitureScorer.MIN_SAMPLE).isEmpty());
        assertTrue(FurnitureScorer.score(BigDecimal.ZERO, prices(400, 420, 450, 500, 600),
                FurnitureScorer.MIN_SAMPLE).isEmpty());
    }

    @Test
    void medianHandlesEvenCount() {
        assertEquals(0, BigDecimal.valueOf(250).compareTo(FurnitureScorer.median(prices(200, 300))));
    }
}
