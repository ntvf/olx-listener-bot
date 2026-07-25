package io.chatbots.olx.furniture;

import io.chatbots.olx.furniture.entity.FurnitureCatalogCrawl;
import io.chatbots.olx.furniture.entity.FurnitureCatalogPrice;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FurnitureCatalogScraperTest {

    private final FurnitureCatalogPriceRepository catalogRepository = mock(FurnitureCatalogPriceRepository.class);
    private final FurnitureCatalogCrawlRepository crawlRepository = mock(FurnitureCatalogCrawlRepository.class);
    private final FurnitureCatalogScraper scraper = new FurnitureCatalogScraper(
            catalogRepository, crawlRepository, true, java.time.Duration.ofDays(45), 0);

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
    void aggregatesMinPerVariantAndModelThenClearsStaging() {
        // two BILLY 80s (min 259) and one BILLY 40 (149); model-level min is 149
        when(crawlRepository.findPriced()).thenReturn(List.of(
                staged("…/billy-a/", "BILLY", "W80", 279),
                staged("…/billy-b/", "BILLY", "W80", 259),
                staged("…/billy-c/", "BILLY", "W40", 149)));

        scraper.aggregateAndSwap();

        ArgumentCaptor<List<FurnitureCatalogPrice>> saved = ArgumentCaptor.forClass(List.class);
        verify(catalogRepository).deleteAllInBatch();          // old catalog swapped out
        verify(catalogRepository).saveAll(saved.capture());
        Map<String, BigDecimal> byKey = saved.getValue().stream().collect(Collectors.toMap(
                r -> r.getModel() + "/" + r.getVariant(), FurnitureCatalogPrice::getPrice));
        assertEquals(0, new BigDecimal("259").compareTo(byKey.get("BILLY/W80")));
        assertEquals(0, new BigDecimal("149").compareTo(byKey.get("BILLY/W40")));
        assertEquals(0, new BigDecimal("149").compareTo(byKey.get("BILLY/null")), "model-level 'from' min");
        verify(crawlRepository).deleteAllInBatch();            // staging cleared on completion
    }

    private FurnitureCatalogCrawl staged(String url, String model, String variant, int price) {
        return FurnitureCatalogCrawl.builder().url(url).model(model).variant(variant)
                .price(BigDecimal.valueOf(price)).currency("PLN").fetchedAt(Instant.now()).build();
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
