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
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Posts owner-verdict offers to their channels once they are old enough. The delay gives
 * the feed time to accumulate context and skips listings that get deleted right away.
 */
@Slf4j
@RequiredArgsConstructor
public class ChannelPublisher {

    static final Duration COMPARABLES_WINDOW = Duration.ofDays(30);

    /** OLX category URLs carry the search city as the path segment after wynajem/sprzedaz. */
    private static final Pattern CITY_IN_URL =
            Pattern.compile("/(?:wynajem|sprzedaz|wynajem-dlugoterminowy)/([\\p{L}][\\p{L}-]+)/");

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
        if (offer.getImageUrl() != null && trySendPhoto(feed, offer, text, markup)) {
            return;
        }
        telegramClient.execute(SendMessage.builder()
                .chatId(feed.getChannelChatId())
                .text(text)
                .replyMarkup(markup)
                .build());
    }

    /**
     * Attempts a photo post. Returns {@code false} — signalling a text-only fallback — when Telegram
     * rejects the image itself with a 400 (e.g. the listing's image URL is dead or serves a web page,
     * not an image: "wrong type of the web page content"). That failure is permanent for this offer,
     * so retrying the photo would stall every owner offer queued behind it. Transient failures
     * (network, 5xx, rate limits) propagate so the offer simply retries on the next tick.
     */
    private boolean trySendPhoto(ChannelFeed feed, FeedOffer offer, String text, InlineKeyboardMarkup markup)
            throws Exception {
        try {
            telegramClient.execute(SendPhoto.builder()
                    .chatId(feed.getChannelChatId())
                    .photo(new InputFile(offer.getImageUrl()))
                    .caption(StringUtils.abbreviate(text, 1024))
                    .replyMarkup(markup)
                    .build());
            return true;
        } catch (TelegramApiRequestException e) {
            if (e.getErrorCode() != null && e.getErrorCode() == 400) {
                log.warn("Photo rejected for offer {}, posting as text instead: {}", offer.getId(), e.getMessage());
                return false;
            }
            throw e;
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
        String tags = buildTags(feed, offer);
        if (!tags.isEmpty()) {
            sb.append(tags).append('\n');
        }
        // channel handle as plain text so a forwarded/screenshotted post still routes back
        if (channel != null && StringUtils.isNotBlank(channel.getUsername())) {
            sb.append("📢 @").append(channel.getUsername()).append('\n');
        }
        sb.append("🔗 ").append(offer.getUrl());
        return sb.toString();
    }

    /**
     * Hashtags derived from the parsed listing: a city+rooms and a district+rooms composite
     * (e.g. {@code #warszawa_2pok #mokotow_2pok}). Composites give subscribers an AND-style
     * filter in one tap, since Telegram hashtag search only ORs across separate tags. When the
     * room count is unknown the bare city/district is used; a district equal to the city is
     * dropped to avoid a duplicate.
     */
    String buildTags(ChannelFeed feed, FeedOffer offer) {
        String city = slug(cityFromUrl(feed.getFeedUrl()));
        String district = slug(lastSegment(offer.getLocation()));
        if (district != null && district.equals(city)) district = null;
        String rooms = roomsTag(offer.getRooms());

        List<String> tags = new ArrayList<>();
        addTag(tags, withRooms(city, rooms));
        addTag(tags, withRooms(district, rooms));

        StringBuilder sb = new StringBuilder();
        for (String tag : tags) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('#').append(tag);
        }
        return sb.toString();
    }

    private static String withRooms(String base, String rooms) {
        if (base == null) return null;
        return rooms == null ? base : base + "_" + rooms;
    }

    private static void addTag(List<String> tags, String tag) {
        if (tag != null && !tags.contains(tag)) tags.add(tag);
    }

    private static String cityFromUrl(String url) {
        if (url == null) return null;
        Matcher m = CITY_IN_URL.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** The most specific part of a location string, e.g. "Warszawa, Mokotów" -> "Mokotów". */
    private static String lastSegment(String location) {
        if (StringUtils.isBlank(location)) return null;
        int comma = location.lastIndexOf(',');
        return comma < 0 ? location : location.substring(comma + 1);
    }

    private static String roomsTag(Integer rooms) {
        if (rooms == null) return null;
        return rooms == 1 ? "kawalerka" : rooms + "pok";
    }

    /** Diacritic-folded, lower-case slug safe for a hashtag: ASCII letters/digits and underscores. */
    static String slug(String s) {
        if (StringUtils.isBlank(s)) return null;
        String folded = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('ł', 'l');
        String slug = folded.replaceAll("[\\s-]+", "_")
                .replaceAll("[^a-z0-9_]", "")
                .replaceAll("_+", "_");
        slug = StringUtils.strip(slug, "_");
        return slug.isEmpty() ? null : slug;
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
