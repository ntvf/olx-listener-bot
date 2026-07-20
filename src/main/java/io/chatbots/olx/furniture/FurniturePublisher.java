package io.chatbots.olx.furniture;

import io.chatbots.olx.channel.ChannelRepository;
import io.chatbots.olx.channel.entity.Channel;
import io.chatbots.olx.furniture.entity.FurnitureFeed;
import io.chatbots.olx.furniture.entity.FurnitureOffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Posts used-IKEA deals to their channels once they are old enough and cheap enough. A deal is a
 * whole-unit listing at least {@code minDiscountPct} below its model median (see
 * {@link FurnitureScorer}). Like the rental channel, a channel gets at most one <b>burst</b> of
 * posts per {@code minPostInterval}: every due deal across <i>all</i> the channel's model feeds
 * goes out together, and only the first message notifies (the rest are silent), so a burst is one
 * buzz. Overnight posts are silenced entirely.
 */
@Slf4j
@RequiredArgsConstructor
public class FurniturePublisher {

    /** The night (silent) window is evaluated in the channel's local (Warsaw) time, not server UTC. */
    private static final ZoneId POST_ZONE = ZoneId.of("Europe/Warsaw");

    /** Label of the inline URL button that deep-links back to the channel. */
    private static final String SUBSCRIBE_BUTTON = "📢 Więcej okazji →";

    private final FurnitureFeedRepository feedRepository;
    private final FurnitureOfferRepository offerRepository;
    private final ChannelRepository channelRepository;
    private final TelegramClient telegramClient;
    private final Duration postDelay;
    /** Minimum spacing between two bursts to the same channel. */
    private final Duration minPostInterval;
    /** Listings whose real creation time is older than this are stale (bumped-old) and never posted. */
    private final Duration maxListingAge;
    /** How far back same-model comparables are drawn for the median. */
    private final Duration comparablesWindow;
    /** A listing must be at least this far below the model median to count as a deal. */
    private final int minDiscountPct;
    private final int silentFromHour;
    private final int silentToHour;

    /** One deal ready to post: the listing and its score against the model median. */
    record Deal(FurnitureOffer offer, FurnitureScorer.Score score) {
    }

    public void publishDue() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(postDelay);
        Instant minCreated = now.minus(maxListingAge);

        Map<Long, List<FurnitureFeed>> feedsByChannel = new LinkedHashMap<>();
        for (FurnitureFeed feed : feedRepository.findByActiveTrue()) {
            feedsByChannel.computeIfAbsent(feed.getChannelChatId(), k -> new ArrayList<>()).add(feed);
        }
        for (Map.Entry<Long, List<FurnitureFeed>> entry : feedsByChannel.entrySet()) {
            long chat = entry.getKey();
            try {
                Instant lastPost = offerRepository.findMaxPostedAtByChannelChatId(chat);
                if (lastPost != null && lastPost.isAfter(now.minus(minPostInterval))) continue;
                publishBurst(entry.getValue(), cutoff, minCreated);
            } catch (Exception e) {
                log.error("Failed to publish furniture deals to channel {}", chat, e);
            }
        }
    }

    /** Collects every due deal across all of one channel's model feeds and posts them as one burst. */
    private void publishBurst(List<FurnitureFeed> feeds, Instant cutoff, Instant minCreated) {
        List<Deal> deals = new ArrayList<>();
        for (FurnitureFeed feed : feeds) {
            List<FurnitureOffer> comps = null; // fetched lazily, once per feed
            for (FurnitureOffer offer : offerRepository.findDueOffers(feed.getId(), cutoff, minCreated)) {
                if (comps == null) {
                    comps = offerRepository.findByFeedIdAndPartFalseAndPriceIsNotNullAndFirstSeenAfter(
                            feed.getId(), Instant.now().minus(comparablesWindow));
                }
                dealFor(offer, comps).ifPresent(deals::add);
            }
        }
        deals.sort((a, b) -> effectiveTime(a.offer()).compareTo(effectiveTime(b.offer())));

        boolean alreadyNotified = false;
        for (Deal deal : deals) {
            boolean silent = alreadyNotified || silentNow(Instant.now());
            try {
                send(deal, silent);
            } catch (Exception e) {
                log.warn("Failed to post furniture offer {} to channel {}",
                        deal.offer().getId(), channelOf(deal), e);
                continue;
            }
            alreadyNotified = alreadyNotified || !silent;
            deal.offer().setPostedAt(Instant.now());
            offerRepository.save(deal.offer());
        }
    }

    /** A deal only if the model median holds enough comparables and the ask clears the discount bar. */
    private Optional<Deal> dealFor(FurnitureOffer offer, List<FurnitureOffer> comps) {
        List<BigDecimal> sameModel = new ArrayList<>();
        for (FurnitureOffer c : comps) {
            if (c.getId().equals(offer.getId())) continue;
            if (Objects.equals(c.getModel(), offer.getModel())) sameModel.add(c.getPrice());
        }
        return FurnitureScorer.score(offer.getPrice(), sameModel, FurnitureScorer.MIN_SAMPLE)
                .filter(s -> s.isDealAtLeast(minDiscountPct))
                .map(s -> new Deal(offer, s));
    }

    private void send(Deal deal, boolean silent) throws Exception {
        Channel channel = channelRepository.findById(channelOf(deal)).orElse(null);
        String text = buildText(deal, channel);
        InlineKeyboardMarkup markup = subscribeButton(channel);
        if (deal.offer().getImageUrl() != null && trySendPhoto(deal, text, markup, silent)) {
            return;
        }
        telegramClient.execute(SendMessage.builder()
                .chatId(channelOf(deal))
                .text(text)
                .replyMarkup(markup)
                .disableNotification(silent)
                .build());
    }

    private long channelOf(Deal deal) {
        return feedRepository.findById(deal.offer().getFeedId())
                .map(FurnitureFeed::getChannelChatId).orElse(0L);
    }

    String buildText(Deal deal, Channel channel) {
        FurnitureOffer offer = deal.offer();
        FurnitureScorer.Score score = deal.score();
        String cur = displayCurrency(offer);

        StringBuilder sb = new StringBuilder();
        sb.append("🛋 ").append(cleanTitle(offer.getTitle())).append('\n');
        // the discount is the shareable hook, so it leads: "🔥 −73% · 120 zł (med 450 zł)"
        sb.append("🔥 ").append(signedPct(score.diffPct()))
                .append(" · ").append(formatAmount(offer.getPrice())).append(' ').append(cur)
                .append(" (med ").append(formatAmount(score.median())).append(' ').append(cur).append(")")
                .append(" · n=").append(score.sampleSize()).append('\n');
        String tags = buildTags(offer);
        if (!tags.isEmpty()) sb.append(tags).append('\n');
        if (channel != null && StringUtils.isNotBlank(channel.getUsername())) {
            sb.append("📢 @").append(channel.getUsername()).append('\n');
        }
        sb.append("🔗 ").append(offer.getUrl());
        return sb.toString();
    }

    /** Hashtags for one-tap filtering: {@code #ikea #malm}. */
    String buildTags(FurnitureOffer offer) {
        StringBuilder sb = new StringBuilder("#ikea");
        String model = slug(offer.getModel());
        if (model != null) sb.append(" #").append(model);
        return sb.toString();
    }

    private boolean trySendPhoto(Deal deal, String text, InlineKeyboardMarkup markup, boolean silent)
            throws Exception {
        try {
            telegramClient.execute(SendPhoto.builder()
                    .chatId(channelOf(deal))
                    .photo(new InputFile(deal.offer().getImageUrl()))
                    .caption(StringUtils.abbreviate(text, 1024))
                    .replyMarkup(markup)
                    .disableNotification(silent)
                    .build());
            return true;
        } catch (TelegramApiRequestException e) {
            if (e.getErrorCode() != null && e.getErrorCode() == 400) {
                log.warn("Photo rejected for furniture offer {}, posting as text instead: {}",
                        deal.offer().getId(), e.getMessage());
                return false;
            }
            throw e;
        }
    }

    private InlineKeyboardMarkup subscribeButton(Channel channel) {
        if (channel == null || StringUtils.isBlank(channel.getUsername())) return null;
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder()
                .text(SUBSCRIBE_BUTTON)
                .url("https://t.me/" + channel.getUsername())
                .build());
        return InlineKeyboardMarkup.builder().keyboard(List.of(row)).build();
    }

    private static Instant effectiveTime(FurnitureOffer offer) {
        return offer.getListingCreatedAt() != null ? offer.getListingCreatedAt() : offer.getPublishedAt();
    }

    boolean silentNow(Instant now) {
        return withinWindow(now.atZone(POST_ZONE).getHour(), silentFromHour, silentToHour);
    }

    static boolean withinWindow(int hour, int from, int to) {
        if (from == to) return false;
        return from < to ? hour >= from && hour < to : hour >= from || hour < to;
    }

    private static String signedPct(int pct) {
        String sign = pct > 0 ? "+" : pct < 0 ? "−" : "±";
        return sign + Math.abs(pct) + "%";
    }

    private static String cleanTitle(String title) {
        return StringUtils.defaultString(title).replaceAll("\\s+,", ",").replaceAll("\\s{2,}", " ").trim();
    }

    private static String displayCurrency(FurnitureOffer offer) {
        return "PLN".equalsIgnoreCase(offer.getCurrency()) ? "zł"
                : StringUtils.defaultString(offer.getCurrency());
    }

    private static String formatAmount(BigDecimal amount) {
        String plain = amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
        return plain.replaceAll("(\\d)(?=(\\d{3})+$)", "$1 ");
    }

    static String slug(String s) {
        if (StringUtils.isBlank(s)) return null;
        String slug = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return slug.isEmpty() ? null : slug;
    }
}
