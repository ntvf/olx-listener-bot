package io.chatbots.olx.grabber;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OfferKeyTest {

    /**
     * The Liquibase backfill rewrites offer_hash with Postgres
     * {@code md5(coalesce(substring(url from '-ID([0-9A-Za-z]+)'), url))}. If that diverged from the
     * runtime key {@code DigestUtils.md5Hex(OfferKey.of(url))}, every live listing would look new
     * after deploy and re-flood the channel. Expected values are the Postgres md5() output for the
     * same URLs (oracle), so this pins the two implementations together.
     */
    @Test
    void javaMd5MatchesPostgresMd5OnExtractedKey() {
        assertHashEquals("07aa94a51e003ec8900411cf7c7aab75",
                "https://www.olx.pl/d/oferta/do-wynajecia-cicha-i-przytulna-kawalerka-na-zoliborzu-CID3-ID1bA1FD.html");
        assertHashEquals("6226ae2d2abbcc62dcf8a20a68ce9ab6",
                "https://www.otodom.pl/pl/oferta/mieszkanie-na-woli-z-duzym-balkonem-i-garazem-ID4Cm5b");
        // no -ID segment: falls back to hashing the full URL, still matching Postgres
        assertHashEquals("5cf3770ca1ac3c38501bf133aba281c4",
                "https://olx.ba/artikal/12345/kawalerka");
        assertHashEquals("aef8854924b26799a3e9729761e834e7",
                "https://www.otodom.pl/pl/oferta/na-wynajem-43-m-pokoj-z-kuchnia-centrum-dwustronne-wysoki-sufit");
    }

    private static void assertHashEquals(String expectedPostgresMd5, String url) {
        assertEquals(expectedPostgresMd5, DigestUtils.md5Hex(OfferKey.of(url)));
    }

    @Test
    void sameAdWithEditedSlugYieldsSameKey() {
        String a = "https://www.olx.pl/d/oferta/do-wynajecia-cicha-i-przytulna-kawalerka-na-zoliborzu-w-viii-kolonii-CID3-ID1bA1FD.html";
        String b = "https://www.olx.pl/d/oferta/do-wynajecia-cicha-i-przytulna-kawalerka-na-zoliborzu-CID3-ID1bA1FD.html";
        assertEquals(OfferKey.of(a), OfferKey.of(b));
        assertEquals("1bA1FD", OfferKey.of(a));
    }

    @Test
    void differentAdsYieldDifferentKeys() {
        String a = "https://www.olx.pl/d/oferta/kawalerka-CID3-ID1bA1FD.html";
        String b = "https://www.olx.pl/d/oferta/kawalerka-CID3-ID9zZ9zZ.html";
        assertEquals("1bA1FD", OfferKey.of(a));
        assertEquals("9zZ9zZ", OfferKey.of(b));
    }

    @Test
    void otodomUrlWithoutHtmlSuffixKeysOnAdId() {
        String a = "https://www.otodom.pl/pl/oferta/mieszkanie-na-woli-z-balkonem-ID4Cm5b";
        String b = "https://www.otodom.pl/pl/oferta/mieszkanie-na-woli-ID4Cm5b";
        assertEquals(OfferKey.of(a), OfferKey.of(b));
        assertEquals("4Cm5b", OfferKey.of(a));
    }

    @Test
    void otomotoOlxIdKeysOnAdIdAcrossEditedSlugs() {
        String a = "https://www.otomoto.pl/osobowe/oferta/seat-cordoba-1-4-salon-polska-OLX_ID6IaTcN.html";
        String b = "https://www.otomoto.pl/osobowe/oferta/seat-cordoba-OLX_ID6IaTcN.html";
        assertEquals(OfferKey.of(a), OfferKey.of(b));
        assertEquals("6IaTcN", OfferKey.of(a));
        // some Otomoto URLs carry the plain -ID form instead of OLX_ID
        assertEquals("6IaTcb", OfferKey.of("https://www.otomoto.pl/osobowe/oferta/peugeot-407-ID6IaTcb.html"));
    }

    @Test
    void nonOlxUrlFallsBackToFullUrl() {
        String url = "https://olx.ba/artikal/12345/kawalerka";
        assertEquals(url, OfferKey.of(url));
    }
}
