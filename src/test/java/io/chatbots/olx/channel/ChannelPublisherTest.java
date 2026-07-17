package io.chatbots.olx.channel;

import io.chatbots.olx.channel.entity.Channel;
import io.chatbots.olx.channel.entity.ChannelFeed;
import io.chatbots.olx.channel.entity.FeedOffer;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelPublisherTest {

    private final FeedOfferRepository offerRepository = mock(FeedOfferRepository.class);
    private final ChannelPublisher publisher = new ChannelPublisher(
            mock(ChannelFeedRepository.class), offerRepository, mock(ChannelRepository.class),
            mock(TelegramClient.class), Duration.ofMinutes(60), 5);

    private ChannelFeed feed() {
        return ChannelFeed.builder().id(1L).channelChatId(-100L).label("kawalerkiWarszawa").build();
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
        assertTrue(text.contains("#kawalerkiWarszawa"), text);
        assertTrue(text.contains("2 800 zł + 400 zł czynsz"), text);
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
}
