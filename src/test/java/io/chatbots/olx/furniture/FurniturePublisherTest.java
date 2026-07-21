package io.chatbots.olx.furniture;

import io.chatbots.olx.channel.ChannelRepository;
import io.chatbots.olx.channel.entity.Channel;
import io.chatbots.olx.furniture.entity.FurnitureFeed;
import io.chatbots.olx.furniture.entity.FurnitureOffer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FurniturePublisherTest {

    private final FurnitureFeedRepository feedRepository = mock(FurnitureFeedRepository.class);
    private final FurnitureOfferRepository offerRepository = mock(FurnitureOfferRepository.class);
    private final ChannelRepository channelRepository = mock(ChannelRepository.class);
    private final TelegramClient telegramClient = mock(TelegramClient.class);

    /** Publisher with the night window disabled (from==to) so the first burst message deterministically notifies. */
    private FurniturePublisher publisher(int minDiscountPct) {
        return new FurniturePublisher(feedRepository, offerRepository, channelRepository, telegramClient,
                Duration.ofMinutes(60), Duration.ofMinutes(60), Duration.ofHours(48),
                Duration.ofDays(35), minDiscountPct, 0, 0);
    }

    private FurnitureFeed feed() {
        return FurnitureFeed.builder().id(1L).channelChatId(-100L)
                .feedUrl("https://www.olx.pl/oferty/q-ikea/warszawa/?search%5Border%5D=created_at:desc").build();
    }

    private FurnitureOffer offer(long id, double price) {
        return FurnitureOffer.builder().id(id).feedId(1L).model("MALM")
                .title("Szafka nocna MALM Ikea").price(BigDecimal.valueOf(price)).currency("PLN")
                .publishedAt(Instant.now().minus(Duration.ofHours(2)))
                .url("https://www.olx.pl/d/oferta/x-ID" + id + ".html").build();
    }

    private List<FurnitureOffer> comps(double price, int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> FurnitureOffer.builder().id(1000L + i).feedId(1L).model("MALM")
                        .price(BigDecimal.valueOf(price)).build())
                .toList();
    }

    private void wireFeed() {
        when(feedRepository.findByActiveTrue()).thenReturn(List.of(feed()));
        when(feedRepository.findById(1L)).thenReturn(Optional.of(feed()));
    }

    @Test
    void postsDealBelowThresholdWithDiscountLead() throws Exception {
        wireFeed();
        when(channelRepository.findById(-100L)).thenReturn(Optional.of(
                Channel.builder().chatId(-100L).username("ikea_okazje_waw").build()));
        when(offerRepository.findDueOffers(eq(1L), any(), any())).thenReturn(List.of(offer(10L, 120)));
        when(offerRepository.findByFeedIdAndPartFalseAndPriceIsNotNullAndFirstSeenAfter(eq(1L), any()))
                .thenReturn(comps(450, 5));

        publisher(25).publishDue();

        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(sent.capture());
        String text = sent.getValue().getText();
        assertTrue(text.contains("🛋 Szafka nocna MALM Ikea"), text);
        assertTrue(text.contains("🔥 −73% · 120 zł (med 450 zł) · n=5"), text);
        assertTrue(text.contains("#ikea_malm"), text);
        assertTrue(text.contains("📢 @ikea_okazje_waw"), text);
        assertTrue(text.contains("🔗 https://www.olx.pl/d/oferta/x-ID10.html"), text);
    }

    @Test
    void skipsListingNotCheapEnough() throws Exception {
        wireFeed();
        // 400 vs median 450 = -11%, below the -25% deal bar
        when(offerRepository.findDueOffers(eq(1L), any(), any())).thenReturn(List.of(offer(10L, 400)));
        when(offerRepository.findByFeedIdAndPartFalseAndPriceIsNotNullAndFirstSeenAfter(eq(1L), any()))
                .thenReturn(comps(450, 5));

        FurnitureOffer due = offer(10L, 400);
        when(offerRepository.findDueOffers(eq(1L), any(), any())).thenReturn(List.of(due));

        publisher(25).publishDue();

        verify(telegramClient, never()).execute(any(SendMessage.class));
        assertNull(due.getPostedAt());
    }

    @Test
    void skipsWhenModelMedianHasTooFewComparables() throws Exception {
        wireFeed();
        when(offerRepository.findDueOffers(eq(1L), any(), any())).thenReturn(List.of(offer(10L, 120)));
        when(offerRepository.findByFeedIdAndPartFalseAndPriceIsNotNullAndFirstSeenAfter(eq(1L), any()))
                .thenReturn(comps(450, 4)); // below MIN_SAMPLE

        publisher(25).publishDue();

        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    @Test
    void burstPostsAllDealsButOnlyFirstNotifies() throws Exception {
        wireFeed();
        when(offerRepository.findDueOffers(eq(1L), any(), any()))
                .thenReturn(List.of(offer(10L, 120), offer(11L, 130), offer(12L, 140)));
        when(offerRepository.findByFeedIdAndPartFalseAndPriceIsNotNullAndFirstSeenAfter(eq(1L), any()))
                .thenReturn(comps(450, 6));

        publisher(25).publishDue();

        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, times(3)).execute(sent.capture());
        List<SendMessage> messages = sent.getAllValues();
        assertFalse(messages.get(0).getDisableNotification());
        assertTrue(messages.get(1).getDisableNotification());
        assertTrue(messages.get(2).getDisableNotification());
    }

    @Test
    void skipsChannelWithinMinPostInterval() throws Exception {
        wireFeed();
        FurnitureOffer due = offer(10L, 120);
        when(offerRepository.findDueOffers(eq(1L), any(), any())).thenReturn(List.of(due));
        when(offerRepository.findByFeedIdAndPartFalseAndPriceIsNotNullAndFirstSeenAfter(eq(1L), any()))
                .thenReturn(comps(450, 6));
        // last burst 3 minutes ago, below the 60-minute spacing
        when(offerRepository.findMaxPostedAtByChannelChatId(anyLong()))
                .thenReturn(Instant.now().minusSeconds(180));

        publisher(25).publishDue();

        verify(telegramClient, never()).execute(any(SendMessage.class));
        assertNull(due.getPostedAt());
    }

    @Test
    void nightBurstIsFullySilent() throws Exception {
        wireFeed();
        // night window covers the whole day (0->24) so nothing buzzes
        FurniturePublisher p = new FurniturePublisher(feedRepository, offerRepository, channelRepository,
                telegramClient, Duration.ofMinutes(60), Duration.ofMinutes(60), Duration.ofHours(48),
                Duration.ofDays(35), 25, 0, 24);
        when(offerRepository.findDueOffers(eq(1L), any(), any()))
                .thenReturn(List.of(offer(10L, 120), offer(11L, 130)));
        when(offerRepository.findByFeedIdAndPartFalseAndPriceIsNotNullAndFirstSeenAfter(eq(1L), any()))
                .thenReturn(comps(450, 6));

        p.publishDue();

        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, times(2)).execute(sent.capture());
        assertTrue(sent.getAllValues().get(0).getDisableNotification());
        assertTrue(sent.getAllValues().get(1).getDisableNotification());
    }

    @Test
    void marksPostedOnSuccess() throws Exception {
        wireFeed();
        FurnitureOffer due = offer(10L, 120);
        when(offerRepository.findDueOffers(eq(1L), any(), any())).thenReturn(List.of(due));
        when(offerRepository.findByFeedIdAndPartFalseAndPriceIsNotNullAndFirstSeenAfter(eq(1L), any()))
                .thenReturn(comps(450, 6));

        publisher(25).publishDue();

        assertNotNull(due.getPostedAt());
    }

    @Test
    void fireCountScalesWithDiscount() {
        assertEquals("🔥", FurniturePublisher.fireTier(-30));
        assertEquals("🔥", FurniturePublisher.fireTier(-39));
        assertEquals("🔥🔥", FurniturePublisher.fireTier(-40));
        assertEquals("🔥🔥", FurniturePublisher.fireTier(-59));
        assertEquals("🔥🔥🔥", FurniturePublisher.fireTier(-62));
    }

    @Test
    void postTextLeadsWithScaledFire() throws Exception {
        wireFeed();
        when(channelRepository.findById(-100L)).thenReturn(Optional.of(
                Channel.builder().chatId(-100L).username("ikea_waw_deals").build()));
        when(offerRepository.findDueOffers(eq(1L), any(), any())).thenReturn(List.of(offer(10L, 300)));
        when(offerRepository.findByFeedIdAndPartFalseAndPriceIsNotNullAndFirstSeenAfter(eq(1L), any()))
                .thenReturn(comps(800, 6)); // 300 vs 800 = -63% (HALF_UP)

        publisher(25).publishDue();

        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(sent.capture());
        assertTrue(sent.getValue().getText().contains("🔥🔥🔥 −63%"), sent.getValue().getText());
    }

    @Test
    void nightWindowWrapsPastMidnight() {
        assertTrue(FurniturePublisher.withinWindow(23, 22, 8));
        assertTrue(FurniturePublisher.withinWindow(2, 22, 8));
        assertFalse(FurniturePublisher.withinWindow(14, 22, 8));
        assertFalse(FurniturePublisher.withinWindow(3, 0, 0));
    }
}
