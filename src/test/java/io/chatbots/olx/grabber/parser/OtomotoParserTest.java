package io.chatbots.olx.grabber.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.chatbots.olx.grabber.Offer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtomotoParserTest {

    private final OtomotoParser parser = new OtomotoParser();

    @Test
    void forcesNewestFirstSort() {
        assertEquals("https://www.otomoto.pl/osobowe?search%5Border%5D=created_at_first%3Adesc",
                OtomotoParser.newestFirst("https://www.otomoto.pl/osobowe"));
    }

    @Test
    void keepsPriceFilterButReplacesOrderAndDropsPaging() {
        String out = OtomotoParser.newestFirst(
                "https://www.otomoto.pl/osobowe?search%5Bfilter_float_price%3Ato%5D=10000"
                        + "&search%5Border%5D=filter_float_price%3Aasc&page=2");
        assertTrue(out.startsWith("https://www.otomoto.pl/osobowe?search%5Border%5D=created_at_first%3Adesc"));
        assertTrue(out.contains("search%5Bfilter_float_price%3Ato%5D=10000")); // the <=10k ceiling survives
        assertFalse(out.contains("page=2"));
        assertFalse(out.contains("filter_float_price%3Aasc"));
    }

    @Test
    void buildsOffersFromTheUrqlAdvertSearchBlob() throws Exception {
        List<Offer> offers = parser.offersFrom(fixture(
                "{\"node\":{\"url\":\"https://www.otomoto.pl/osobowe/oferta/seat-cordoba-OLX_ID6IaTcN.html\","
                        + "\"title\":\"Seat Cordoba\",\"shortDescription\":\"1.4 16V Salon Polska\","
                        + "\"isPremiumTopAd\":true,\"createdAt\":\"2026-07-27T21:15:18Z\"}},"
                        + "{\"node\":{\"url\":\"https://www.otomoto.pl/osobowe/oferta/opel-corsa-OLX_ID6IaTcG.html\","
                        + "\"title\":\"Opel Corsa\",\"createdAt\":\"2026-07-27T21:14:47Z\"}}"));

        assertEquals(2, offers.size());
        Offer first = offers.get(0);
        assertEquals("https://www.otomoto.pl/osobowe/oferta/seat-cordoba-OLX_ID6IaTcN.html", first.getUrl());
        assertEquals("Seat Cordoba", first.getName());
        assertEquals("1.4 16V Salon Polska", first.getContent());
        assertTrue(first.isPromoted());
        assertEquals(Instant.parse("2026-07-27T21:15:18Z"), first.getCreatedAt());
        assertFalse(offers.get(1).isPromoted());
    }

    @Test
    void skipsNodesMissingUrlOrTitle() throws Exception {
        List<Offer> offers = parser.offersFrom(fixture(
                "{\"node\":{\"url\":\"https://www.otomoto.pl/osobowe/oferta/ok-OLX_ID6a.html\",\"title\":\"Good\","
                        + "\"createdAt\":\"2026-07-27T10:00:00Z\"}},"
                        + "{\"node\":{\"title\":\"No url\"}},"
                        + "{\"node\":{\"url\":\"https://www.otomoto.pl/osobowe/oferta/no-title-OLX_ID6b.html\"}}"));
        assertEquals(1, offers.size());
        assertEquals("Good", offers.get(0).getName());
    }

    @Test
    void returnsEmptyWhenNextDataAbsent() throws Exception {
        assertTrue(parser.offersFrom(Jsoup.parse("<html><body>blocked</body></html>")).isEmpty());
    }

    /** Wraps edge nodes in the stringified urql cache blob Otomoto ships inside __NEXT_DATA__. */
    private static Document fixture(String edgesJson) throws Exception {
        String inner = "{\"advertSearch\":{\"__typename\":\"AdvertSearchOutput\",\"edges\":[" + edgesJson + "]}}";
        String escaped = new ObjectMapper().writeValueAsString(inner); // quoted + escaped, as the cache stores it
        String outer = "{\"props\":{\"pageProps\":{\"urqlState\":{\"-4010808129\":"
                + "{\"hasNext\":false,\"data\":" + escaped + "}}}}}";
        return Jsoup.parse("<html><body><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + outer + "</script></body></html>");
    }
}
