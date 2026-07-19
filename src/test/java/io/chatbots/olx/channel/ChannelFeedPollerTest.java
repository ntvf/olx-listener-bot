package io.chatbots.olx.channel;

import io.chatbots.olx.channel.entity.ChannelFeed;
import io.chatbots.olx.channel.entity.FeedOffer;
import io.chatbots.olx.grabber.Offer;
import io.chatbots.olx.grabber.OlxGrabber;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelFeedPollerTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private final Instant firstSeen = Instant.parse("2026-07-18T18:00:00Z");

    @Test
    void bumpedOldListingIsStoredButNotPosted() {
        // created 10 days ago but only now surfaced (bumped to the top) -> suppressed via posted_at
        FeedOffer saved = poll(Instant.now().minus(Duration.ofDays(10)));
        assertNotNull(saved.getPostedAt());
        assertNotNull(saved.getListingCreatedAt());
    }

    @Test
    void freshListingIsQueuedForPosting() {
        // created minutes ago -> genuinely new -> left unposted for the publisher to pick up
        FeedOffer saved = poll(Instant.now().minus(Duration.ofMinutes(5)));
        assertNull(saved.getPostedAt());
    }

    @Test
    void listingWithoutCreationTimeFailsOpen() {
        // detail page didn't expose a creation time -> don't suppress a possibly-new listing
        FeedOffer saved = poll(null);
        assertNull(saved.getPostedAt());
    }

    /** Runs one poll cycle over a single offer enriched with the given creation time; returns the saved row. */
    private FeedOffer poll(Instant createdAt) {
        ChannelFeedRepository feedRepository = mock(ChannelFeedRepository.class);
        FeedOfferRepository offerRepository = mock(FeedOfferRepository.class);
        OlxGrabber grabber = mock(OlxGrabber.class);
        ListingEnricher enricher = mock(ListingEnricher.class);
        ChannelFeedPoller poller = new ChannelFeedPoller(
                feedRepository, offerRepository, grabber, enricher, Duration.ofHours(48));

        ChannelFeed feed = ChannelFeed.builder().id(1L).feedUrl("https://www.olx.pl/feed").build();
        // non-empty hash set -> not a baseline run, so posted_at reflects the freshness gate alone
        when(offerRepository.findHashesByFeedId(1L)).thenReturn(Set.of("prev"));
        when(grabber.getOffers("https://www.olx.pl/feed")).thenReturn(List.of(
                Offer.builder().url("https://www.olx.pl/d/oferta/x-CID3-ID1.html")
                        .name("Kawalerka od właściciela").build()));
        when(enricher.enrich(any())).thenReturn(ListingEnricher.Enriched.builder()
                .price(BigDecimal.valueOf(2500)).currency("PLN").createdAt(createdAt).build());

        poller.pollFeed(feed);

        ArgumentCaptor<FeedOffer> captor = ArgumentCaptor.forClass(FeedOffer.class);
        verify(offerRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void preciseCardTimeIsZonedFromWarsaw() {
        // "Dzisiaj o 19:16" -> 19:16 Warsaw (summer, UTC+2) = 17:16Z, not first_seen
        LocalDateTime card = LocalDateTime.of(2026, 7, 18, 19, 16);
        assertEquals(Instant.parse("2026-07-18T17:16:00Z"),
                ChannelFeedPoller.publishInstant(card, firstSeen));
    }

    @Test
    void dateOnlyCardFallsBackToFirstSeen() {
        // older listings show only a date -> parsed to midnight -> unreliable, use discovery time
        LocalDateTime midnight = LocalDate.of(2026, 7, 15).atStartOfDay();
        assertEquals(firstSeen, ChannelFeedPoller.publishInstant(midnight, firstSeen));
    }

    @Test
    void nullCardFallsBackToFirstSeen() {
        assertEquals(firstSeen, ChannelFeedPoller.publishInstant(null, firstSeen));
    }

    @Test
    void warsawZoneIsApplied() {
        // sanity: the same wall clock zoned to Warsaw differs from treating it as UTC
        LocalDateTime card = LocalDateTime.of(2026, 7, 18, 12, 0);
        assertEquals(card.atZone(WARSAW).toInstant(),
                ChannelFeedPoller.publishInstant(card, firstSeen));
    }
}
