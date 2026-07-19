package io.chatbots.olx.channel;

import io.chatbots.olx.channel.entity.Channel;
import io.chatbots.olx.channel.entity.ChannelFeed;
import io.chatbots.olx.channel.entity.FeedOffer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelPublisherTest {

    private final FeedOfferRepository offerRepository = mock(FeedOfferRepository.class);
    private final ChannelFeedRepository feedRepository = mock(ChannelFeedRepository.class);
    private final TelegramClient telegramClient = mock(TelegramClient.class);
    private final ListingEnricher enricher = mock(ListingEnricher.class);
    private final ChannelPublisher publisher = new ChannelPublisher(
            feedRepository, offerRepository, mock(ChannelRepository.class),
            telegramClient, enricher, Duration.ofMinutes(60), Duration.ofMinutes(10), 22, 8);

    private static TelegramApiRequestException apiError(int code) {
        TelegramApiRequestException e = mock(TelegramApiRequestException.class);
        when(e.getErrorCode()).thenReturn(code);
        return e;
    }

    private void queueDue(FeedOffer... offers) {
        when(feedRepository.findByActiveTrue()).thenReturn(List.of(feed()));
        when(offerRepository.findByFeedIdAndPostedAtIsNullAndVerdictAndDirectTrueAndPublishedAtBeforeOrderByPublishedAtAsc(
                anyLong(), any(), any())).thenReturn(List.of(offers));
    }

    private ChannelFeed feed() {
        return ChannelFeed.builder().id(1L).channelChatId(-100L)
                .feedUrl("https://www.olx.pl/nieruchomosci/mieszkania/wynajem/warszawa/?search%5Border%5D=created_at:desc")
                .build();
    }

    private FeedOffer offer() {
        return FeedOffer.builder()
                .id(10L).feedId(1L)
                .title("Kawalerka 28 m² Mokotów, od właściciela")
                .price(BigDecimal.valueOf(2800)).currency("PLN")
                .extraRent(BigDecimal.valueOf(400))
                .areaM2(BigDecimal.valueOf(28)).rooms(1)
                .location("Mokotów")
                .url("https://www.olx.pl/d/oferta/x-CID3-ID1.html")
                .build();
    }

    @Test
    void includesChannelHandleAndCta() {
        // no comparables -> no score line, keeps the assertion focused on the footer
        when(offerRepository.findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(anyLong(), any()))
                .thenReturn(List.of());
        Channel channel = Channel.builder().chatId(-100L).username("bez_posrednika_waw").build();

        String text = publisher.buildText(feed(), offer(), channel);

        assertTrue(text.contains("📢 @bez_posrednika_waw"), text);
        assertTrue(text.contains("🔗 https://www.olx.pl/d/oferta/x-CID3-ID1.html"), text);
        assertTrue(text.contains("#warszawa_kawalerka #mokotow_kawalerka"), text);
        assertTrue(text.contains("💰 3 200 zł (2 800 + 400 czynsz)"), text);
        assertTrue(text.contains("📐 28 m² · 🚪 1 · 📍 Mokotów"), text);
    }

    @Test
    void autoTagsAreCityRoomsAndDistrictRoomsComposites() {
        FeedOffer offer = FeedOffer.builder().location("Praga-Południe").rooms(2).build();
        assertEquals("#warszawa_2pok #praga_poludnie_2pok", publisher.buildTags(feed(), offer));
    }

    @Test
    void autoTagsSkipMissingDistrict() {
        FeedOffer offer = FeedOffer.builder().location(null).rooms(1).build();
        assertEquals("#warszawa_kawalerka", publisher.buildTags(feed(), offer));
    }

    @Test
    void autoTagsDropDistrictEqualToCity() {
        FeedOffer offer = FeedOffer.builder().location("Warszawa").rooms(3).build();
        assertEquals("#warszawa_3pok", publisher.buildTags(feed(), offer));
    }

    @Test
    void autoTagsFallBackToBareCityDistrictWhenRoomsUnknown() {
        FeedOffer offer = FeedOffer.builder().location("Mokotów").rooms(null).build();
        assertEquals("#warszawa #mokotow", publisher.buildTags(feed(), offer));
    }

    @Test
    void skipsHandleWhenChannelUnknownOrPublic() {
        when(offerRepository.findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(anyLong(), any()))
                .thenReturn(List.of());

        String noChannel = publisher.buildText(feed(), offer(), null);
        assertFalse(noChannel.contains("📢"), noChannel);

        Channel noUsername = Channel.builder().chatId(-100L).username("  ").build();
        assertFalse(publisher.buildText(feed(), offer(), noUsername).contains("📢"));
    }

    private FeedOffer comp(long id, String location, Integer rooms, double total, double area) {
        return FeedOffer.builder().id(id).feedId(1L)
                .location(location).rooms(rooms)
                .price(BigDecimal.valueOf(total)).areaM2(BigDecimal.valueOf(area))
                .build();
    }

    private FeedOffer scoredOffer() {
        return FeedOffer.builder().id(1L).feedId(1L)
                .title("2 pokoje Mokotów").price(BigDecimal.valueOf(4000)).currency("PLN")
                .areaM2(BigDecimal.valueOf(50)).rooms(2).location("Mokotów")
                .url("https://www.olx.pl/d/oferta/x.html").build();
    }

    @Test
    void scoreLineSegmentsByDistrictAndRoomsComparesTotals() {
        // 6 same-district same-rooms comps at 5000 total; offer totals 4000 = -20% on totals
        List<FeedOffer> pool = IntStream.rangeClosed(100, 105)
                .mapToObj(i -> comp(i, "Mokotów", 2, 5000, 50)).toList();
        when(offerRepository.findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(anyLong(), any()))
                .thenReturn(pool);

        String text = publisher.buildText(feed(), scoredOffer(), null);

        assertTrue(text.contains("📊 −20% 🟢 · 4 000 vs 5 000 zł · n=6"), text);
        assertTrue(text.contains("📊 −20% 🟢 · 80 vs 100 zł/m²"), text);
    }

    @Test
    void scoreLineFallsBackToDistrictWhenRoomsBucketTooThin() {
        // 4 same-rooms (below the district+rooms bar of 6) but 10 same-district in total
        List<FeedOffer> pool = Stream.concat(
                IntStream.rangeClosed(100, 103).mapToObj(i -> comp(i, "Mokotów", 2, 5000, 50)),
                IntStream.rangeClosed(200, 205).mapToObj(i -> comp(i, "Mokotów", 3, 5000, 50))).toList();
        when(offerRepository.findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(anyLong(), any()))
                .thenReturn(pool);

        String text = publisher.buildText(feed(), scoredOffer(), null);

        // district-level per-m² line with the flat's market estimate, not the totals comparison
        assertTrue(text.contains("📊 −20% 🟢 · 80 vs 100 zł/m² · ~5 000 zł · n=10"), text);
        assertFalse(text.contains("4 000 vs"), text);
    }

    @Test
    void scoreLineFallsBackToWholeFeedLabelledCity() {
        // no same-district comps; 10 elsewhere -> whole-feed level, labelled from the feed city
        List<FeedOffer> pool = IntStream.rangeClosed(300, 309)
                .mapToObj(i -> comp(i, "Wola", 2, 5000, 50)).toList();
        when(offerRepository.findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(anyLong(), any()))
                .thenReturn(pool);

        String text = publisher.buildText(feed(), scoredOffer(), null);

        assertTrue(text.contains("📊 −20% 🟢 · 80 vs 100 zł/m² · ~5 000 zł · n=10"), text);
    }

    @Test
    void rejectedPhotoIsPostedAsTextNotStalled() throws Exception {
        when(offerRepository.findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(anyLong(), any()))
                .thenReturn(List.of());
        FeedOffer badImage = offer().toBuilder().id(10L).imageUrl("https://dead.example/x.jpg").build();
        queueDue(badImage);
        // Telegram rejects the dead image with a permanent 400 -> degrade to a text post
        TelegramApiRequestException rejected = apiError(400);
        when(telegramClient.execute(any(SendPhoto.class))).thenThrow(rejected);

        publisher.publishDue();

        // posted as text (so it doesn't block the queue), not left stuck at the head
        verify(telegramClient).execute(any(SendMessage.class));
        assertNotNull(badImage.getPostedAt());
    }

    @Test
    void transientPhotoFailureStopsTickWithoutTextFallback() throws Exception {
        when(offerRepository.findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(anyLong(), any()))
                .thenReturn(List.of());
        FeedOffer offer = offer().toBuilder().id(10L).imageUrl("https://cdn.example/x.jpg").build();
        queueDue(offer);
        // 5xx / rate-limit style failure: retry next tick, do not degrade to text
        TelegramApiRequestException transient_ = apiError(500);
        when(telegramClient.execute(any(SendPhoto.class))).thenThrow(transient_);

        publisher.publishDue();

        verify(telegramClient, never()).execute(any(SendMessage.class));
        assertNull(offer.getPostedAt());
    }

    @Test
    void postsAtMostOneOfferPerTick() throws Exception {
        when(offerRepository.findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(anyLong(), any()))
                .thenReturn(List.of());
        FeedOffer first = offer().toBuilder().id(10L).imageUrl(null).build();
        FeedOffer second = offer().toBuilder().id(11L).imageUrl(null).build();
        queueDue(first, second);

        publisher.publishDue();

        // only the oldest due offer goes out this tick; the rest wait for the next one
        verify(telegramClient).execute(any(SendMessage.class));
        assertNotNull(first.getPostedAt());
        assertNull(second.getPostedAt());
    }

    @Test
    void nightWindowWrapsPastMidnight() {
        // 22 -> 8 window: silent late night / early morning, audible during the day
        assertTrue(ChannelPublisher.withinWindow(23, 22, 8));
        assertTrue(ChannelPublisher.withinWindow(2, 22, 8));
        assertTrue(ChannelPublisher.withinWindow(22, 22, 8));
        assertFalse(ChannelPublisher.withinWindow(8, 22, 8));
        assertFalse(ChannelPublisher.withinWindow(14, 22, 8));
        // from == to disables silencing entirely
        assertFalse(ChannelPublisher.withinWindow(3, 0, 0));
    }

    @Test
    void dropsCzynszAtOrAboveRent() {
        when(offerRepository.findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(anyLong(), any()))
                .thenReturn(List.of());
        // czynsz == rent (deposit mistyped into the field) and an absurd czynsz are both ignored:
        // the total is just the rent, with no "+ czynsz" breakdown
        FeedOffer equalToRent = offer().toBuilder().price(BigDecimal.valueOf(6000))
                .extraRent(BigDecimal.valueOf(6000)).build();
        FeedOffer absurd = offer().toBuilder().price(BigDecimal.valueOf(5000))
                .extraRent(BigDecimal.valueOf(136931)).build();

        String t1 = publisher.buildText(feed(), equalToRent, null);
        assertTrue(t1.contains("💰 6 000 zł"), t1);
        assertFalse(t1.contains("czynsz"), t1);

        String t2 = publisher.buildText(feed(), absurd, null);
        assertTrue(t2.contains("💰 5 000 zł"), t2);
        assertFalse(t2.contains("czynsz"), t2);
    }

    @Test
    void keepsCzynszBelowRent() {
        when(offerRepository.findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(anyLong(), any()))
                .thenReturn(List.of());
        // the base offer() has a plausible 2800 + 400 czynsz -> shown, total 3200
        String text = publisher.buildText(feed(), offer(), null);
        assertTrue(text.contains("💰 3 200 zł (2 800 + 400 czynsz)"), text);
    }

    @Test
    void implausibleCzynszDoesNotInflateOwnVerdict() {
        // 6 same-district same-rooms comps at 6000 total; offer's stored 6000+6000 must NOT read as +100%
        List<FeedOffer> pool = IntStream.rangeClosed(100, 105)
                .mapToObj(i -> comp(i, "Mokotów", 2, 6000, 50)).toList();
        when(offerRepository.findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(anyLong(), any()))
                .thenReturn(pool);
        FeedOffer bogus = scoredOffer().toBuilder().price(BigDecimal.valueOf(6000))
                .extraRent(BigDecimal.valueOf(6000)).build();

        String text = publisher.buildText(feed(), bogus, null);

        assertTrue(text.contains("📊 ±0% 🟡 · 6 000 vs 6 000 zł · n=6"), text);
        assertFalse(text.contains("12 000"), text);
    }

    @Test
    void reEnrichesImplausibleRentBeforePublishing() throws Exception {
        when(offerRepository.findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(anyLong(), any()))
                .thenReturn(List.of());
        FeedOffer stale = offer().toBuilder().id(10L).imageUrl(null)
                .price(BigDecimal.valueOf(6000)).extraRent(BigDecimal.valueOf(6000)).build();
        queueDue(stale);
        // by publish time the poster has corrected czynsz to 1250; re-fetch recovers it
        when(enricher.enrich(stale.getUrl())).thenReturn(ListingEnricher.Enriched.builder()
                .price(BigDecimal.valueOf(6000)).currency("PLN").extraRent(BigDecimal.valueOf(1250)).build());

        publisher.publishDue();

        verify(enricher).enrich(stale.getUrl());
        assertEquals(BigDecimal.valueOf(1250), stale.getExtraRent());
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(sent.capture());
        assertTrue(sent.getValue().getText().contains("💰 7 250 zł (6 000 + 1 250 czynsz)"),
                sent.getValue().getText());
    }

    @Test
    void doesNotReEnrichPlausibleRent() throws Exception {
        when(offerRepository.findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(anyLong(), any()))
                .thenReturn(List.of());
        FeedOffer ok = offer().toBuilder().id(10L).imageUrl(null).build(); // 2800 + 400, plausible
        queueDue(ok);

        publisher.publishDue();

        verify(enricher, never()).enrich(any());
    }

    @Test
    void skipsChannelWithinMinPostInterval() throws Exception {
        FeedOffer due = offer().toBuilder().id(10L).imageUrl(null).build();
        queueDue(due);
        // last post to this channel was 3 minutes ago — below the 10-minute spacing
        when(offerRepository.findMaxPostedAtByChannelChatId(anyLong()))
                .thenReturn(Instant.now().minusSeconds(180));

        publisher.publishDue();

        verify(telegramClient, never()).execute(any(SendMessage.class));
        assertNull(due.getPostedAt());
    }
}
