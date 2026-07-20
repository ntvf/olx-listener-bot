package io.chatbots.olx.furniture;

import io.chatbots.olx.channel.ListingEnricher;
import io.chatbots.olx.furniture.entity.FurnitureFeed;
import io.chatbots.olx.furniture.entity.FurnitureOffer;
import io.chatbots.olx.grabber.Offer;
import io.chatbots.olx.grabber.OlxGrabber;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FurnitureFeedPollerTest {

    private final Instant firstSeen = Instant.parse("2026-07-18T18:00:00Z");

    @Test
    void wholeUnitIsStoredNotAsPart() {
        FurnitureOffer saved = poll("Szafka nocna MALM Ikea biała", BigDecimal.valueOf(120),
                Instant.now().minus(Duration.ofMinutes(5)));
        assertEquals("MALM", saved.getModel());
        assertFalse(saved.isPart());
        assertNull(saved.getPostedAt()); // fresh, queued for the publisher
    }

    @Test
    void partListingIsStoredFlagged() {
        FurnitureOffer saved = poll("Drzwi do szafy PAX Ikea", BigDecimal.valueOf(90),
                Instant.now().minus(Duration.ofMinutes(5)));
        assertTrue(saved.isPart());
    }

    @Test
    void nonIkeaCollisionIsStoredFlagged() {
        // a "Billy" bike tyre from the broad q=ikea feed: no ikea token -> not our unit, no model
        FurnitureOffer saved = poll("Opona Schwalbe Billy Bonkers 26", BigDecimal.valueOf(120),
                Instant.now().minus(Duration.ofMinutes(5)));
        assertTrue(saved.isPart());
        assertNull(saved.getModel());
    }

    @Test
    void modelIsDetectedFromTitleNotPinnedOnFeed() {
        // one broad feed carries mixed models; each is detected from its own title
        assertEquals("HEMNES", poll("Komoda HEMNES Ikea biała", BigDecimal.valueOf(300),
                Instant.now().minus(Duration.ofMinutes(5))).getModel());
    }

    @Test
    void baselineRunSeedsHistoryWithoutPosting() {
        FurnitureOffer saved = poll("Szafka MALM Ikea", BigDecimal.valueOf(200),
                Instant.now().minus(Duration.ofMinutes(5)), true);
        assertNotNull(saved.getPostedAt()); // baseline -> not flooded to the channel
    }

    private FurnitureOffer poll(String title, BigDecimal price, Instant createdAt) {
        return poll(title, price, createdAt, false);
    }

    /** Runs one poll cycle over a single offer; returns the saved row. */
    private FurnitureOffer poll(String title, BigDecimal price, Instant createdAt, boolean baseline) {
        FurnitureFeedRepository feedRepository = mock(FurnitureFeedRepository.class);
        FurnitureOfferRepository offerRepository = mock(FurnitureOfferRepository.class);
        OlxGrabber grabber = mock(OlxGrabber.class);
        ListingEnricher enricher = mock(ListingEnricher.class);
        FurnitureFeedPoller poller = new FurnitureFeedPoller(
                feedRepository, offerRepository, grabber, enricher, 0);

        FurnitureFeed feed = FurnitureFeed.builder().id(1L)
                .feedUrl("https://www.olx.pl/feed").build();

        when(offerRepository.findHashesByFeedId(1L)).thenReturn(baseline ? Set.of() : Set.of("prev"));
        when(grabber.getOffers("https://www.olx.pl/feed")).thenReturn(List.of(
                Offer.builder().url("https://www.olx.pl/d/oferta/x-CID3-ID1.html").name(title).build()));
        when(enricher.enrich(any())).thenReturn(ListingEnricher.Enriched.builder()
                .price(price).currency("PLN").createdAt(createdAt).build());

        poller.pollFeed(feed);

        ArgumentCaptor<FurnitureOffer> captor = ArgumentCaptor.forClass(FurnitureOffer.class);
        verify(offerRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void preciseCardTimeIsZonedFromWarsaw() {
        LocalDateTime card = LocalDateTime.of(2026, 7, 18, 19, 16);
        assertEquals(Instant.parse("2026-07-18T17:16:00Z"),
                FurnitureFeedPoller.publishInstant(card, firstSeen));
    }

    @Test
    void dateOnlyCardFallsBackToFirstSeen() {
        LocalDateTime midnight = LocalDate.of(2026, 7, 15).atStartOfDay();
        assertEquals(firstSeen, FurnitureFeedPoller.publishInstant(midnight, firstSeen));
    }

    @Test
    void nullCardFallsBackToFirstSeen() {
        assertEquals(firstSeen, FurnitureFeedPoller.publishInstant(null, firstSeen));
    }
}
