package io.chatbots.olx.channel;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListingEnricherTest {

    private final ListingEnricher enricher = new ListingEnricher();

    /**
     * Mirrors a live olx.pl detail page fetched 2026-07 (a Praga-Południe kawalerka):
     * JSON-LD carries a numeric price and the district in offers.areaServed.name, the
     * script tag has a data-rh attribute, params render as a pipe-ish list with extra
     * rows, "Firmowe" marks a business seller, and the profile link uses
     * data-testid=user-profile-link with an /oferty/uzytkownik/<slug>/ href. Styled-
     * components inject <style> blocks inside the params container — Jsoup drops them.
     */
    @Test
    void parsesOlxDetailPage() {
        String html = """
                <html><head>
                <script data-rh="true" type="application/ld+json">
                {"@context":"https://schema.org","@type":"Product",
                 "name":"Kawalerka blisko Ronda Wiatraczna",
                 "offers":{"@type":"Offer","availability":"https://schema.org/InStock",
                   "areaServed":{"@type":"AdministrativeArea","name":"Praga-Południe"},
                   "priceCurrency":"PLN","price":2000}}
                </script>
                <meta property="og:image" content="https://ireland.apollo.olxcdn.com/v1/files/abc-PL/image"/>
                </head><body>
                <div data-testid="ad-parameters-container">
                  <style data-emotion="css 1jmwd0g">.css-1jmwd0g{cursor:auto;}</style>
                  <p>Firmowe</p><p>Zwierzęta: Nie</p><p>Winda: Nie</p><p>Poziom: 2</p>
                  <p>Umeblowane: Tak</p><p>Rodzaj zabudowy: Apartamentowiec</p>
                  <p>Powierzchnia: 20 m²</p><p>Liczba pokoi: Kawalerka</p>
                  <p>Czynsz (dodatkowo): 300 zł</p>
                </div>
                <div data-cy="ad_description">Oferta bezpośrednia bez dodatkowych płatności.</div>
                <a href="/oferty/uzytkownik/vGIqN/" data-testid="user-profile-link" name="user_ads">Sebastian</a>
                </body></html>
                """;
        ListingEnricher.Enriched e = enricher.parse(Jsoup.parse(html));

        assertEquals(0, e.price().compareTo(BigDecimal.valueOf(2000)));
        assertEquals("PLN", e.currency());
        assertEquals(0, e.areaM2().compareTo(BigDecimal.valueOf(20)));
        assertEquals(1, e.rooms());
        assertEquals(0, e.extraRent().compareTo(BigDecimal.valueOf(300)));
        assertTrue(e.sellerBusiness());
        assertEquals("vGIqN", e.sellerId());
        assertEquals("Praga-Południe", e.location());
        assertEquals("https://ireland.apollo.olxcdn.com/v1/files/abc-PL/image", e.imageUrl());
        assertTrue(e.description().contains("bezpośrednia"));
    }

    @Test
    void parsesPrivateOwnerListing() {
        String html = """
                <html><head>
                <script type="application/ld+json">
                {"@type":"Product","offers":{"priceCurrency":"PLN","price":3200}}
                </script>
                </head><body>
                <div data-testid="ad-parameters-container">
                  <p>Prywatne</p><p>Powierzchnia: 48 m²</p><p>Liczba pokoi: 2</p>
                  <p>Czynsz (dodatkowo): 550 zł</p>
                </div>
                <div data-cy="ad_description">Wynajmę bezpośrednio, bez prowizji.</div>
                <a href="/oferty/uzytkownik/aBcDe/">Zobacz profil</a>
                </body></html>
                """;
        ListingEnricher.Enriched e = enricher.parse(Jsoup.parse(html));

        assertEquals(0, e.price().compareTo(BigDecimal.valueOf(3200)));
        assertEquals(0, e.areaM2().compareTo(BigDecimal.valueOf(48)));
        assertEquals(2, e.rooms());
        assertEquals(0, e.extraRent().compareTo(BigDecimal.valueOf(550)));
        assertFalse(e.sellerBusiness());
        assertEquals("aBcDe", e.sellerId());
        assertTrue(e.description().contains("bez prowizji"));
    }

    /**
     * When OLX's structured "Czynsz (dodatkowo)" field is blank, the extra fee is recovered from the
     * description. Mirrors the live Saska Kępa ad where "Czynsz najmu to 4200 zł" names the base rent
     * with the word czynsz — only the "plus 800 zł ... media" clause is the additional fee.
     */
    @Test
    void recoversExtraRentFromDescriptionWhenParamMissing() {
        String html = """
                <html><head>
                <script type="application/ld+json">
                {"@type":"Product","offers":{"priceCurrency":"PLN","price":4200}}
                </script></head><body>
                <div data-testid="ad-parameters-container">
                  <p>Prywatne</p><p>Powierzchnia: 60 m²</p><p>Liczba pokoi: 2</p>
                </div>
                <div data-cy="ad_description">Czynsz najmu to 4200 zł plus 800 zł zaliczka na media. Wymagana kaucja.</div>
                </body></html>
                """;
        ListingEnricher.Enriched e = enricher.parse(Jsoup.parse(html));
        assertEquals(0, e.price().compareTo(BigDecimal.valueOf(4200)));
        assertEquals(0, e.extraRent().compareTo(BigDecimal.valueOf(800)));
    }

    /** Otodom too: when the ad has no structured "rent" characteristic, czynsz is recovered from the description. */
    @Test
    void recoversOtodomExtraRentFromDescription() {
        String html = """
                <html><body>
                <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"ad":{
                  "advertType":"PRIVATE",
                  "characteristics":[{"key":"price","value":"3500"},{"key":"m","value":"42"},{"key":"rooms_num","value":"2"}],
                  "location":{"reverseGeocoding":{"locations":[{"locationLevel":"city_or_village","name":"Warszawa"}]}},
                  "owner":{"id":77,"name":"Jan"},
                  "description":"<p>Do najmu 3500 zł, czynsz administracyjny 480 zł.</p>"
                }}}}
                </script></body></html>
                """;
        ListingEnricher.Enriched e = enricher.parseOtodom(Jsoup.parse(html));
        assertEquals(0, e.price().compareTo(BigDecimal.valueOf(3500)));
        assertEquals(0, e.extraRent().compareTo(BigDecimal.valueOf(480)));
    }

    /** A structured "rent" characteristic always wins; the description is not consulted. */
    @Test
    void otodomStructuredRentWinsOverDescription() {
        String html = """
                <html><body>
                <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"ad":{
                  "characteristics":[{"key":"price","value":"3500"},{"key":"rent","value":"600"}],
                  "description":"<p>czynsz administracyjny 480 zł</p>"
                }}}}
                </script></body></html>
                """;
        ListingEnricher.Enriched e = enricher.parseOtodom(Jsoup.parse(html));
        assertEquals(0, e.extraRent().compareTo(BigDecimal.valueOf(600)));
    }

    @Test
    void czynszFromDescriptionVariants() {
        assertEquals(0, ListingEnricher.czynszFromDescription(
                "Cena 3000 zł, czynsz administracyjny 500 zł.").compareTo(BigDecimal.valueOf(500)));
        assertEquals(0, ListingEnricher.czynszFromDescription(
                "Do tego czynsz do spółdzielni: 620 zł.").compareTo(BigDecimal.valueOf(620)));
        assertEquals(0, ListingEnricher.czynszFromDescription(
                "Najem plus 450 zł opłaty administracyjne.").compareTo(BigDecimal.valueOf(450)));
        assertEquals(0, ListingEnricher.czynszFromDescription(
                "Do zapłaty 1 200 zł zaliczki na media miesięcznie.").compareTo(BigDecimal.valueOf(1200)));
        // base rent carrying the word "czynsz" must never be captured as the extra fee
        assertNull(ListingEnricher.czynszFromDescription("Czynsz najmu wynosi 4200 zł, media wg zużycia."));
        assertNull(ListingEnricher.czynszFromDescription("Bezpośrednio, bez dodatkowych opłat."));
        assertNull(ListingEnricher.czynszFromDescription(null));
    }

    @Test
    void kawalerkaMeansOneRoom() {
        String html = """
                <html><body><div data-testid="ad-parameters-container">
                Firmowe Powierzchnia: 30,5 m² Liczba pokoi: Kawalerka
                </div></body></html>
                """;
        ListingEnricher.Enriched e = enricher.parse(Jsoup.parse(html));
        assertEquals(1, e.rooms());
        assertEquals(0, e.areaM2().compareTo(new BigDecimal("30.5")));
        assertTrue(e.sellerBusiness());
    }

    @Test
    void fallsBackToVisiblePriceAndDescriptionArea() {
        String html = """
                <html><body>
                <div data-testid="ad-price-container">3 200 zł</div>
                <div data-cy="ad_description">Mieszkanie 52 m² na Mokotowie</div>
                </body></html>
                """;
        ListingEnricher.Enriched e = enricher.parse(Jsoup.parse(html));
        assertEquals(0, e.price().compareTo(BigDecimal.valueOf(3200)));
        assertEquals("PLN", e.currency());
        assertEquals(0, e.areaM2().compareTo(BigDecimal.valueOf(52)));
    }

    /**
     * Mirrors an Otodom detail page: the whole offer ships as JSON in the __NEXT_DATA__ blob,
     * so price/rent/area/rooms come from characteristics, the advertiser type from advertType,
     * the seller id from owner.id, the phone from contactDetails, and the district from the
     * reverse-geocoding ladder. OLX selectors match nothing here — this path replaces them.
     */
    @Test
    void parsesOtodomNextData() {
        String html = """
                <html><body>
                <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"ad":{
                  "advertType":"AGENCY",
                  "characteristics":[
                    {"key":"price","value":"4700"},
                    {"key":"rent","value":"1100"},
                    {"key":"m","value":"56.96"},
                    {"key":"rooms_num","value":"2"}],
                  "location":{"reverseGeocoding":{"locations":[
                    {"locationLevel":"voivodeship","name":"mazowieckie"},
                    {"locationLevel":"city_or_village","name":"Warszawa"},
                    {"locationLevel":"district","name":"Włochy"},
                    {"locationLevel":"residential","name":"Nowe Włochy"}]}},
                  "owner":{"id":10293901,"type":"agency","name":"Exclusive Partners","phones":["+48570704752"]},
                  "contactDetails":{"phones":["+48789365761"]},
                  "description":"<p>Przedwojenna elegancja <b>bez</b> prowizji.</p>",
                  "images":[{"large":"https://img.otodom/large.jpg","medium":"m.jpg"}]
                }}}}
                </script>
                </body></html>
                """;
        ListingEnricher.Enriched e = enricher.parseOtodom(Jsoup.parse(html));

        assertEquals(0, e.price().compareTo(BigDecimal.valueOf(4700)));
        assertEquals("PLN", e.currency());
        assertEquals(0, e.extraRent().compareTo(BigDecimal.valueOf(1100)));
        assertEquals(0, e.areaM2().compareTo(new BigDecimal("56.96")));
        assertEquals(2, e.rooms());
        // district level wins over the narrower "residential" sub-area, matching OLX's bare district
        assertEquals("Włochy", e.location());
        assertTrue(e.sellerBusiness());
        assertEquals("10293901", e.sellerId());
        assertEquals("Exclusive Partners", e.advertiserName());
        assertEquals("48789365761", e.phone()); // contactDetails wins over owner, normalized
        assertEquals("https://img.otodom/large.jpg", e.imageUrl());
        assertTrue(e.description().contains("bez prowizji"));
    }

    @Test
    void privateOtodomListingHasNoBusinessFlagAndFallsBackToOwnerPhone() {
        String html = """
                <html><body>
                <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"ad":{
                  "advertType":"PRIVATE",
                  "characteristics":[{"key":"m","value":"30"},{"key":"rooms_num","value":"1"}],
                  "location":{"reverseGeocoding":{"locations":[
                    {"locationLevel":"city_or_village","name":"Kraków"}]}},
                  "owner":{"id":555,"type":"private","name":"Anna","phones":["+48111222333"]}
                }}}}
                </script>
                </body></html>
                """;
        ListingEnricher.Enriched e = enricher.parseOtodom(Jsoup.parse(html));

        assertFalse(e.sellerBusiness());
        assertEquals(1, e.rooms());
        assertEquals("Kraków", e.location());
        assertEquals("48111222333", e.phone()); // no contactDetails -> owner phone, normalized
        assertEquals("555", e.sellerId());
    }

    /**
     * OLX ships advertiser identity (name, business flag, numeric offer id) and a "phone exists"
     * flag in window.__PRERENDERED_STATE__ — a JS string of escaped JSON. The phone number itself
     * is not here; only whether one can be fetched. Mirrors the live olx.pl shape (ad.ad.*).
     */
    @Test
    void parsesOlxPrerenderedState() {
        String html = """
                <html><body>
                <script>
                window.__PRERENDERED_STATE__ = "{\\"ad\\":{\\"ad\\":{\\"id\\":1085647952,\\"isBusiness\\":true,\\"contact\\":{\\"phone\\":true},\\"user\\":{\\"id\\":638613187,\\"name\\":\\"HORIZON ESTATE\\"}}}}";
                window.OTHER = 1;
                </script>
                </body></html>
                """;
        ListingEnricher.OlxIdentity id = enricher.parseOlxPrerendered(Jsoup.parse(html));

        assertEquals("HORIZON ESTATE", id.sellerName());
        assertTrue(id.business());
        assertEquals("1085647952", id.offerId());
        assertTrue(id.hasPhone());
    }

    @Test
    void missingPrerenderedStateYieldsNull() {
        assertNull(enricher.parseOlxPrerendered(Jsoup.parse("<html><body>no state here</body></html>")));
    }

    @Test
    void phoneNormalizationUnifiesSources() {
        assertEquals("48571310725", ListingEnricher.normalizePhone("571 310 725")); // OLX national
        assertEquals("48570704752", ListingEnricher.normalizePhone("+48570704752")); // Otodom E.164
        assertEquals("48123456789", ListingEnricher.normalizePhone("0048 123 456 789")); // 00 prefix
        assertNull(ListingEnricher.normalizePhone(null));
        assertNull(ListingEnricher.normalizePhone("brak"));
    }

    @Test
    void otodomUrlIsDetected() {
        assertTrue(ListingEnricher.isOtodom("https://www.otodom.pl/pl/oferta/x-ID4BWKL"));
        assertFalse(ListingEnricher.isOtodom("https://www.olx.pl/d/oferta/y.html"));
    }

    @Test
    void sellerIdFromHrefVariants() {
        assertEquals("slug123", ListingEnricher.sellerIdFromHref("https://www.olx.pl/oferty/uzytkownik/slug123/"));
        assertEquals("abc", ListingEnricher.sellerIdFromHref("/d/uk/list/user/abc/?page=1"));
        assertNull(ListingEnricher.sellerIdFromHref("https://www.olx.pl/d/oferta/xyz.html"));
    }

    @Test
    void numberParsingHandlesSpacesAndCommas() {
        assertEquals(0, ListingEnricher.toNumber("3 200").compareTo(BigDecimal.valueOf(3200)));
        assertEquals(0, ListingEnricher.toNumber("30,5").compareTo(new BigDecimal("30.5")));
        assertNull(ListingEnricher.toNumber("abc"));
    }

    @Test
    void creationTimeParsesOlxOffsetAndOtodomZulu() {
        // OLX ships a local offset, Otodom a Zulu time — both resolve to the same instant
        assertEquals(Instant.parse("2026-07-19T14:23:58Z"),
                ListingEnricher.toInstant("2026-07-19T16:23:58+02:00"));
        assertEquals(Instant.parse("2026-07-19T19:09:58Z"),
                ListingEnricher.toInstant("2026-07-19T19:09:58Z"));
        assertNull(ListingEnricher.toInstant(null));
        assertNull(ListingEnricher.toInstant("not-a-date"));
    }
}
