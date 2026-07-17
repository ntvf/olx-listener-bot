package io.chatbots.olx.channel;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
}
