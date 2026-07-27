package io.chatbots.olx.grabber.parser;

import io.chatbots.olx.grabber.Offer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtodomParserTest {

    private final OtodomParser parser = new OtodomParser();

    @Test
    void forcesNewestFirstSort() {
        assertEquals("https://www.otodom.pl/pl/wyniki/wynajem/mieszkanie/warszawa?by=LATEST&direction=DESC",
                OtodomParser.newestFirst("https://www.otodom.pl/pl/wyniki/wynajem/mieszkanie/warszawa"));
    }

    @Test
    void replacesAnyExistingSortAndDropsPaging() {
        String out = OtodomParser.newestFirst(
                "https://www.otodom.pl/pl/wyniki/wynajem/mieszkanie/warszawa?by=PRICE&direction=ASC&page=3&limit=36");
        assertTrue(out.startsWith("https://www.otodom.pl/pl/wyniki/wynajem/mieszkanie/warszawa?by=LATEST&direction=DESC"));
        assertTrue(out.contains("limit=36"));
        assertFalse(out.contains("page=3"));
        assertFalse(out.contains("by=PRICE"));
        assertFalse(out.contains("direction=ASC"));
    }

    @Test
    void buildsOffersFromNextDataKeyedOnOriginalPostTime() throws Exception {
        List<Offer> offers = parser.offersFrom(fixture);

        assertEquals(2, offers.size());

        Offer first = offers.get(0);
        assertEquals("https://www.otodom.pl/pl/oferta/kawalerka-wola-ID4CoGQ", first.getUrl());
        assertEquals("Kawalerka Wola 26 m2", first.getName());
        assertTrue(first.isPromoted());
        // createdAtFirst wins over the later, bumped dateCreated so old re-surfaced listings stay filterable
        assertEquals(Instant.parse("2026-07-27T22:48:58Z"), first.getCreatedAt());

        Offer second = offers.get(1);
        assertEquals("https://www.otodom.pl/pl/oferta/2-pok-mokotow-ID4CoH5", second.getUrl());
        assertFalse(second.isPromoted());
        // no createdAtFirst -> falls back to the Warsaw-local dateCreated (2026-07-20 10:00 +02:00)
        assertEquals(Instant.parse("2026-07-20T08:00:00Z"), second.getCreatedAt());
    }

    @Test
    void skipsItemsMissingSlugOrTitle() throws Exception {
        Document doc = nextData("["
                + "{\"slug\":\"ok-ID4a\",\"title\":\"Good\",\"createdAtFirst\":\"2026-07-27T10:00:00Z\"},"
                + "{\"title\":\"No slug\"},"
                + "{\"slug\":\"no-title-ID4b\",\"title\":\"\"}"
                + "]");
        List<Offer> offers = parser.offersFrom(doc);
        assertEquals(1, offers.size());
        assertEquals("Good", offers.get(0).getName());
    }

    @Test
    void returnsEmptyWhenNextDataAbsent() throws Exception {
        assertTrue(parser.offersFrom(Jsoup.parse("<html><body>blocked</body></html>")).isEmpty());
    }

    private final Document fixture = nextData("["
            + "{\"slug\":\"kawalerka-wola-ID4CoGQ\",\"title\":\"Kawalerka Wola 26 m2\",\"isPromoted\":true,"
            + "\"dateCreated\":\"2026-07-27 22:55:28\",\"createdAtFirst\":\"2026-07-27T22:48:58Z\"},"
            + "{\"slug\":\"2-pok-mokotow-ID4CoH5\",\"title\":\"2 pok Mokotów\",\"isPromoted\":false,"
            + "\"dateCreated\":\"2026-07-20 10:00:00\"}"
            + "]");

    /** Wraps an items array in the deep pageProps nesting Otodom ships, to prove findItems locates it by shape. */
    private static Document nextData(String itemsJson) {
        String json = "{\"props\":{\"pageProps\":{\"data\":{\"searchAds\":{\"items\":" + itemsJson + "}}}}}";
        return Jsoup.parse("<html><body><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + json + "</script></body></html>");
    }
}
