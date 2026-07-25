package io.chatbots.olx.furniture;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class FurnitureCatalogScraperTest {

    private final FurnitureCatalogScraper scraper = new FurnitureCatalogScraper(
            mock(FurnitureCatalogPriceRepository.class), true, java.time.Duration.ofDays(45), 0);

    /** JSON-LD as ikea.pl emits it: name reads like an OLX title, price/currency in the Offer. */
    private static final String BILLY_JSON_LD = """
            {"@context":"https://schema.org/","@type":"Product",
             "name":"BILLY Regał - biały 80x28x202 cm","width":"80 cm","depth":"28 cm","height":"202 cm",
             "description":"BILLY Regał - biały 80x28x202 cm. Regulowane półki.",
             "offers":{"@type":"Offer","availability":"https://schema.org/InStock",
                       "itemCondition":"https://schema.org/NewCondition","price":"279","priceCurrency":"PLN"}}
            """;

    @Test
    void parsesModelVariantAndPriceLikeAListing() {
        FurnitureCatalogScraper.Product p = scraper.parseProduct(
                BILLY_JSON_LD, "https://www.ikea.com/pl/pl/p/billy-regal-bialy-00263850/");
        assertEquals("BILLY", p.model());
        assertEquals("W80", p.variant()); // same key FurnitureVariantParser gives a used BILLY 80
        assertEquals(0, new BigDecimal("279").compareTo(p.price()));
        assertEquals("PLN", p.currency());
    }

    @Test
    void ignoresBlocksWithoutAnOfferPrice() {
        String noOffer = "{\"@type\":\"Product\",\"name\":\"MALM Komoda\"}";
        assertNull(scraper.parseProduct(noOffer, "https://www.ikea.com/pl/pl/p/malm-komoda-1/"));
    }

    @Test
    void slugModelReadsTheLeadingToken() {
        assertEquals("billy", FurnitureCatalogScraper.slugModel(
                "https://www.ikea.com/pl/pl/p/billy-regal-bialy-00263850/"));
        assertEquals("poang", FurnitureCatalogScraper.slugModel(
                "https://www.ikea.com/pl/pl/p/poang-fotel-00000000/")); // folded to ASCII like the model set
        assertEquals("", FurnitureCatalogScraper.slugModel("https://www.ikea.com/pl/pl/cat/regaly/"));
    }
}
