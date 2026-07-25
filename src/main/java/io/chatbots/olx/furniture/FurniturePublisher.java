package io.chatbots.olx.furniture;

import io.chatbots.olx.channel.ChannelRepository;
import io.chatbots.olx.channel.entity.Channel;
import io.chatbots.olx.furniture.entity.FurnitureCatalogPrice;
import io.chatbots.olx.furniture.entity.FurnitureFeed;
import io.chatbots.olx.furniture.entity.FurnitureOffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
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

    private final FurnitureFeedRepository feedRepository;
    private final FurnitureOfferRepository offerRepository;
    private final ChannelRepository channelRepository;
    private final FurnitureCatalogPriceRepository catalogRepository;
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
                    comps = offerRepository.findSizedComparables(
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

    /**
     * A deal only if the median group holds enough comparables and the ask clears the discount bar.
     * Prefers the tight {@code model+variant} group (a BILLY 80 vs other BILLY 80s); falls back to
     * the bare-model median when that variant group is too small, so finer grouping never loses a
     * postable deal versus the old per-model behaviour.
     */
    private Optional<Deal> dealFor(FurnitureOffer offer, List<FurnitureOffer> comps) {
        List<BigDecimal> sameVariant = new ArrayList<>();
        List<BigDecimal> sameModel = new ArrayList<>();
        for (FurnitureOffer c : comps) {
            if (c.getId().equals(offer.getId())) continue;
            if (!Objects.equals(c.getModel(), offer.getModel())) continue;
            sameModel.add(c.getPrice());
            if (offer.getVariant() != null && Objects.equals(c.getVariant(), offer.getVariant())) {
                sameVariant.add(c.getPrice());
            }
        }
        List<BigDecimal> group = sameVariant.size() >= FurnitureScorer.MIN_SAMPLE ? sameVariant : sameModel;
        return FurnitureScorer.score(offer.getPrice(), group, FurnitureScorer.MIN_SAMPLE)
                .filter(s -> s.isDealAtLeast(minDiscountPct))
                .map(s -> new Deal(offer, s));
    }

    private void send(Deal deal, boolean silent) throws Exception {
        Channel channel = channelRepository.findById(channelOf(deal)).orElse(null);
        String text = buildText(deal, channel);
        if (deal.offer().getImageUrl() != null && trySendPhoto(deal, text, silent)) {
            return;
        }
        telegramClient.execute(SendMessage.builder()
                .chatId(channelOf(deal))
                .text(text)
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
        // the discount is the shareable hook, so it leads; the fire count scales with it:
        // "🔥🔥🔥 −73% · 120 zł (med 450 zł)"
        sb.append(fireTier(score.diffPct())).append(' ').append(signedPct(score.diffPct()))
                .append(" · ").append(formatAmount(offer.getPrice())).append(' ').append(cur)
                .append(" (med ").append(formatAmount(score.median())).append(' ').append(cur).append(")")
                .append(" · n=").append(score.sampleSize()).append('\n');
        String catalogLine = catalogLine(offer, cur);
        if (catalogLine != null) sb.append(catalogLine).append('\n');
        String tags = buildTags(offer);
        if (!tags.isEmpty()) sb.append(tags).append('\n');
        if (channel != null && StringUtils.isNotBlank(channel.getUsername())) {
            sb.append("📢 @").append(channel.getUsername()).append('\n');
        }
        sb.append("🔗 ").append(offer.getUrl());
        return sb.toString();
    }

    /**
     * The optional "vs new" anchor line, e.g. {@code 🏷 −66% vs new (kat. 279 zł)}. Prefers the exact
     * {@code model+variant} catalog price; falls back to the model-level minimum ("kat. od …"). Shown
     * only when the used ask is actually below the new price — a used unit priced at/above new is odd
     * (a bundle, a scam, a mis-key) and a "+% vs new" would read as broken, so the line is dropped.
     * Absent entirely when nothing was scraped for the model, so partial catalog coverage never breaks
     * a post.
     */
    String catalogLine(FurnitureOffer offer, String currency) {
        if (offer.getModel() == null) return null;
        FurnitureCatalogPrice exact = offer.getVariant() == null ? null
                : catalogRepository.findByModelAndVariant(offer.getModel(), offer.getVariant()).orElse(null);
        boolean fromModelLevel = exact == null;
        FurnitureCatalogPrice catalog = fromModelLevel
                ? catalogRepository.findModelLevel(offer.getModel()).orElse(null)
                : exact;
        if (catalog == null || catalog.getPrice().signum() <= 0) return null;
        if (offer.getPrice().compareTo(catalog.getPrice()) >= 0) return null;

        int diffPct = offer.getPrice().subtract(catalog.getPrice())
                .multiply(BigDecimal.valueOf(100))
                .divide(catalog.getPrice(), 0, RoundingMode.HALF_UP)
                .intValue();
        String prefix = fromModelLevel ? "kat. od " : "kat. ";
        return "🏷 " + signedPct(diffPct) + " vs new (" + prefix + formatAmount(catalog.getPrice()) + ' ' + currency + ")";
    }

    /** One composite hashtag for one-tap filtering by model: {@code #ikea_malm} (or {@code #ikea}). */
    String buildTags(FurnitureOffer offer) {
        String model = slug(offer.getModel());
        return model == null ? "#ikea" : "#ikea_" + model;
    }

    private boolean trySendPhoto(Deal deal, String text, boolean silent) throws Exception {
        try {
            telegramClient.execute(SendPhoto.builder()
                    .chatId(channelOf(deal))
                    .photo(new InputFile(deal.offer().getImageUrl()))
                    .caption(StringUtils.abbreviate(text, 1024))
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

    /** Fire count scales with the discount depth: 🔥 (≥25% off), 🔥🔥 (≥40%), 🔥🔥🔥 (≥60%). */
    static String fireTier(int diffPct) {
        int discount = -diffPct; // deals are below the median, so diffPct is negative
        if (discount >= 60) return "🔥🔥🔥";
        if (discount >= 40) return "🔥🔥";
        return "🔥";
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
