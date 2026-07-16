package io.chatbots.olx.score;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MarketPriceParserTest {

    private final MarketPriceParser parser = new MarketPriceParser();

    @Test
    void parsesTheRequestedFormat() {
        MarketPrice price = parser.parse("""
                RANGE: 1200-1800 PLN
                COMPS: 7
                NOTE: Sells within a week if complete.
                """);
        assertNotNull(price);
        assertEquals(1200, price.getLow());
        assertEquals(1800, price.getHigh());
        assertEquals("PLN", price.getCurrency());
        assertEquals(7, price.getComparables());
        assertEquals("Sells within a week if complete.", price.getNote());
    }

    @Test
    void readsThousandsSeparatorsRatherThanFractions() {
        MarketPrice price = parser.parse("RANGE: 1 200-15 500 грн\nCOMPS: 4");
        assertNotNull(price);
        assertEquals(1200, price.getLow());
        assertEquals(15500, price.getHigh());

        MarketPrice dotted = parser.parse("RANGE: 1.200-1.800 zł\nCOMPS: 4");
        assertNotNull(dotted);
        assertEquals(1200, dotted.getLow());
        assertEquals(1800, dotted.getHigh());
    }

    @Test
    void keepsRealFractions() {
        MarketPrice price = parser.parse("RANGE: 10,50-24,99 EUR\nCOMPS: 5");
        assertNotNull(price);
        assertEquals(10.50, price.getLow());
        assertEquals(24.99, price.getHigh());
    }

    @Test
    void noComparablesIsAnAnswerNotAFailure() {
        MarketPrice price = parser.parse("COMPS: 0\nNOTE: Nothing similar on sale right now.");
        assertNotNull(price);
        assertEquals(0, price.getComparables());
        assertEquals("Nothing similar on sale right now.", price.getNote());
    }

    @Test
    void freeformProseFallsBackToNull() {
        assertNull(parser.parse("This looks like a decent deal, you could probably resell it for 1500."));
        assertNull(parser.parse(""));
        assertNull(parser.parse(null));
    }

    @Test
    void backwardsRangeIsRejected() {
        assertNull(parser.parse("RANGE: 1800-1200 PLN\nCOMPS: 6"));
    }

    @Test
    void parsesScrapedListingPrices() {
        assertEquals(1200, parser.parseListingPrice("1 200 zł"));
        assertEquals(1200, parser.parseListingPrice("1200 PLN"));
        assertEquals(15500, parser.parseListingPrice("15 500 грн"));
        assertEquals(99.99, parser.parseListingPrice("99,99 EUR"));
        assertNull(parser.parseListingPrice("Free"));
        assertNull(parser.parseListingPrice(null));
    }
}
