package io.chatbots.olx.channel;

import io.chatbots.olx.channel.entity.Channel;
import io.chatbots.olx.channel.entity.ChannelFeed;
import io.chatbots.olx.channel.entity.FeedOffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Posts owner-verdict offers to their channels once they are old enough. The delay gives
 * the feed time to accumulate context and skips listings that get deleted right away.
 */
@Slf4j
@RequiredArgsConstructor
public class ChannelPublisher {

    static final Duration COMPARABLES_WINDOW = Duration.ofDays(30);

    /** Text CTA kept in the caption itself so it survives screenshots and copy-paste. */
    private static final String SUBSCRIBE_CTA = "нові квартири без комісії · więcej mieszkań";
    /** Label of the inline URL button that deep-links back to the channel. */
    private static final String SUBSCRIBE_BUTTON = "📢 Більше квартир →";

    private final ChannelFeedRepository feedRepository;
    private final FeedOfferRepository offerRepository;
    private final ChannelRepository channelRepository;
    private final TelegramClient telegramClient;
    private final Duration postDelay;
    private final int maxPostsPerTick;

    public void publishDue() {
        Instant cutoff = Instant.now().minus(postDelay);
        for (ChannelFeed feed : feedRepository.findByActiveTrue()) {
            try {
                publishFeed(feed, cutoff);
            } catch (Exception e) {
                log.error("Failed to publish feed {} to channel {}", feed.getId(), feed.getChannelChatId(), e);
            }
        }
    }

    private void publishFeed(ChannelFeed feed, Instant cutoff) {
        List<FeedOffer> due = offerRepository
                .findByFeedIdAndPostedAtIsNullAndVerdictAndFirstSeenBeforeOrderByFirstSeenAsc(
                        feed.getId(), AgencyDetector.Verdict.OWNER.name(), cutoff);
        int posted = 0;
        for (FeedOffer offer : due) {
            if (posted >= maxPostsPerTick) break;
            try {
                send(feed, offer);
            } catch (Exception e) {
                // channel likely unreachable; keep posted_at null so the offer retries next tick
                log.warn("Failed to post offer {} to channel {}", offer.getId(), feed.getChannelChatId(), e);
                break;
            }
            offer.setPostedAt(Instant.now());
            offerRepository.save(offer);
            posted++;
        }
    }

    private void send(ChannelFeed feed, FeedOffer offer) throws Exception {
        Channel channel = channelRepository.findById(feed.getChannelChatId()).orElse(null);
        String text = buildText(feed, offer, channel);
        InlineKeyboardMarkup markup = subscribeButton(channel);
        if (offer.getImageUrl() != null) {
            telegramClient.execute(SendPhoto.builder()
                    .chatId(feed.getChannelChatId())
                    .photo(new InputFile(offer.getImageUrl()))
                    .caption(StringUtils.abbreviate(text, 1024))
                    .replyMarkup(markup)
                    .build());
        } else {
            telegramClient.execute(SendMessage.builder()
                    .chatId(feed.getChannelChatId())
                    .text(text)
                    .replyMarkup(markup)
                    .build());
        }
    }

    /** URL button back to the channel; survives forwarding (unlike callback buttons). */
    private InlineKeyboardMarkup subscribeButton(Channel channel) {
        if (channel == null || StringUtils.isBlank(channel.getUsername())) return null;
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder()
                .text(SUBSCRIBE_BUTTON)
                .url("https://t.me/" + channel.getUsername())
                .build());
        return InlineKeyboardMarkup.builder().keyboard(List.of(row)).build();
    }

    String buildText(ChannelFeed feed, FeedOffer offer, Channel channel) {
        StringBuilder sb = new StringBuilder();
        sb.append("🏠 ").append(StringUtils.defaultString(offer.getTitle())).append('\n');

        if (offer.getPrice() != null) {
            sb.append("💰 ").append(formatAmount(offer.getPrice())).append(' ').append(displayCurrency(offer));
            if (offer.getExtraRent() != null && offer.getExtraRent().signum() > 0) {
                sb.append(" + ").append(formatAmount(offer.getExtraRent())).append(' ')
                        .append(displayCurrency(offer)).append(" czynsz");
            }
            scoreLine(feed, offer).ifPresent(line -> sb.append('\n').append(line));
            sb.append('\n');
        }
        if (offer.getAreaM2() != null) {
            sb.append("📐 ").append(offer.getAreaM2().stripTrailingZeros().toPlainString()).append(" m²");
            if (offer.getRooms() != null) sb.append(" · ").append(offer.getRooms()).append(" pok.");
            sb.append('\n');
        }
        if (offer.getLocation() != null) {
            sb.append("📍 ").append(offer.getLocation()).append('\n');
        }
        if (StringUtils.isNotBlank(feed.getLabel())) {
            sb.append('#').append(feed.getLabel().replaceAll("[^\\p{L}\\p{N}_]", "")).append('\n');
        }
        // channel handle as plain text so a forwarded/screenshotted post still routes back
        if (channel != null && StringUtils.isNotBlank(channel.getUsername())) {
            sb.append("📢 @").append(channel.getUsername()).append(" · ").append(SUBSCRIBE_CTA).append('\n');
        }
        sb.append("🔗 ").append(offer.getUrl());
        return sb.toString();
    }

    private Optional<String> scoreLine(ChannelFeed feed, FeedOffer offer) {
        if (offer.getAreaM2() == null) return Optional.empty();
        List<BigDecimal> comparables = offerRepository
                .findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(
                        feed.getId(), Instant.now().minus(COMPARABLES_WINDOW))
                .stream()
                .filter(o -> !o.getId().equals(offer.getId()))
                .filter(o -> o.getAreaM2().signum() > 0)
                .map(o -> totalPrice(o).divide(o.getAreaM2(), MathContext.DECIMAL64))
                .toList();
        return RentalScorer.score(totalPrice(offer), offer.getAreaM2(), comparables)
                .map(s -> String.format("📊 %s %s/m² · mediana %s (%s%d%%, n=%d)",
                        s.pricePerM2().toPlainString(), displayCurrency(offer),
                        s.medianPerM2().toPlainString(),
                        s.diffPct() > 0 ? "+" : s.diffPct() < 0 ? "−" : "±",
                        Math.abs(s.diffPct()), s.sampleSize()));
    }

    private static BigDecimal totalPrice(FeedOffer offer) {
        BigDecimal extra = offer.getExtraRent() == null ? BigDecimal.ZERO : offer.getExtraRent();
        return offer.getPrice().add(extra);
    }

    private static String displayCurrency(FeedOffer offer) {
        return "PLN".equalsIgnoreCase(offer.getCurrency()) ? "zł"
                : StringUtils.defaultString(offer.getCurrency());
    }

    private static String formatAmount(BigDecimal amount) {
        String plain = amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
        // group thousands with thin spaces: 3200 -> 3 200
        return plain.replaceAll("(\\d)(?=(\\d{3})+$)", "$1 ");
    }
}
