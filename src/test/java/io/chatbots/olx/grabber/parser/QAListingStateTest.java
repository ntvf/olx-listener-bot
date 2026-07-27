package io.chatbots.olx.grabber.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QAListingStateTest {

    @Test
    void listingIdIsTakenFromTheIdToken() {
        assertEquals("1btoKU", QA.listingId("https://www.olx.pl/d/oferta/foo-CID3-ID1btoKU.html"));
        assertEquals("1abc", QA.listingId("https://www.olx.bg/x-ID1abc.html?reason=extended_search"));
        assertEquals("4Cay4", QA.listingId("https://www.otodom.pl/pl/oferta/foo-ID4Cay4")); // no .html suffix
        assertNull(QA.listingId("https://bazaraki.com/adv/123_apartment"));
        assertNull(QA.listingId(null));
    }

    @Test
    void parseInstantHandlesOlxOffset() {
        assertEquals(Instant.parse("2026-07-19T14:23:58Z"), QA.parseInstant("2026-07-19T16:23:58+02:00"));
        assertNull(QA.parseInstant(null));
        assertNull(QA.parseInstant("not-a-date"));
    }

    @Test
    void extractsCreatedTimesFromPrerenderedState() throws Exception {
        // the state ships as a quoted JS string literal holding escaped JSON of listing.listing.ads
        String innerJson = "{\"listing\":{\"listing\":{\"ads\":["
                + "{\"url\":\"https://www.olx.pl/d/oferta/fresh-ID1abc.html\",\"createdTime\":\"2026-07-19T16:23:58+02:00\"},"
                + "{\"url\":\"https://www.olx.pl/d/oferta/bumped-ID2def.html\",\"createdTime\":\"2026-07-05T11:59:26+02:00\"}"
                + "]}}}";
        String literal = new ObjectMapper().writeValueAsString(innerJson); // quoted + escaped, as OLX ships it
        String html = "<html><body><script>window.__PRERENDERED_STATE__ = " + literal + ";</script></body></html>";
        Document doc = Jsoup.parse(html);

        Map<String, Instant> map = new QA().createdTimesById(doc);

        assertEquals(Instant.parse("2026-07-19T14:23:58Z"), map.get("1abc"));
        assertEquals(Instant.parse("2026-07-05T09:59:26Z"), map.get("2def"));
    }

    @Test
    void indexesCreatedTimeUnderTheOtodomExternalUrlToo() throws Exception {
        // OLX cross-posts an Otodom listing: the card links to externalUrl (otodom.pl), so the
        // createdTime must be reachable by the Otodom ad id or the freshness gate never sees it.
        String innerJson = "{\"listing\":{\"listing\":{\"ads\":["
                + "{\"url\":\"https://www.olx.pl/d/oferta/foo-CID3-ID1bqaSG.html\","
                + "\"externalUrl\":\"https://www.otodom.pl/pl/oferta/foo-ID4Cay4\","
                + "\"createdTime\":\"2026-07-12T02:54:53+02:00\"}"
                + "]}}}";
        String literal = new ObjectMapper().writeValueAsString(innerJson);
        Document doc = Jsoup.parse("<html><body><script>window.__PRERENDERED_STATE__ = " + literal + ";</script></body></html>");

        Map<String, Instant> map = new QA().createdTimesById(doc);

        Instant expected = Instant.parse("2026-07-12T00:54:53Z");
        assertEquals(expected, map.get("1bqaSG")); // olx id
        assertEquals(expected, map.get("4Cay4"));  // otodom id from externalUrl
    }

    @Test
    void failsOpenWhenStateAbsent() {
        // no __PRERENDERED_STATE__ (blocked page / other domain markup) -> empty map, callers treat as fresh
        Document doc = Jsoup.parse("<html><body><div>no state here</div></body></html>");
        assertTrue(new QA().createdTimesById(doc).isEmpty());
    }
}
