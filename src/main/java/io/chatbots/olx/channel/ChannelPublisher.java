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
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Posts owner-verdict offers to their channels once they are old enough. The delay gives
 * the feed time to accumulate context and skips listings that get deleted right away.
 */
@Slf4j
@RequiredArgsConstructor
public class ChannelPublisher {

    static final Duration COMPARABLES_WINDOW = Duration.ofDays(35);

    /** The night (silent) window is evaluated in the channel's local (Warsaw) time, not server UTC. */
    private static final ZoneId POST_ZONE = ZoneId.of("Europe/Warsaw");

    /**
     * The tight district+rooms segment is shown at a lower sample bar than the wider fallbacks:
     * splitting by both district and room count thins each bucket, but a like-for-like median of
     * even a handful of same-district same-size flats beats a large mixed one.
     */
    private static final int DISTRICT_ROOMS_MIN_SAMPLE = 6;

    /** OLX category URLs carry the search city as the path segment after wynajem/sprzedaz. */
    private static final Pattern CITY_IN_URL =
            Pattern.compile("/(?:wynajem|sprzedaz|wynajem-dlugoterminowy)/([\\p{L}][\\p{L}-]+)/");

    private final ChannelFeedRepository feedRepository;
    private final FeedOfferRepository offerRepository;
    private final ChannelRepository channelRepository;
    private final TelegramClient telegramClient;
    private final ListingEnricher enricher;
    private final Duration postDelay;
    /** Minimum spacing between two posts to the same channel, so the feed drips rather than bursts. */
    private final Duration minPostInterval;
    /** Listings whose real creation time is older than this are stale (bumped-old) and never posted. */
    private final Duration maxListingAge;
    /**
     * Night window [from, to) in Warsaw local hours during which posts are still sent but with
     * notifications disabled (silent), so overnight listings land without buzzing subscribers.
     * Wraps past midnight when from &gt; to (e.g. 22→8); from==to disables the silencing.
     */
    private final int silentFromHour;
    private final int silentToHour;

    /**
     * Once per {@link #minPostInterval} per channel, posts every listing that is at least
     * {@link #postDelay} old (by real creation time) as a single burst. Only the first message of a
     * burst notifies; the rest are silent, so a burst is one buzz. Owner-direct listings only.
     */
    public void publishDue() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(postDelay);
        Instant minCreated = now.minus(maxListingAge);
        Set<Long> handled = new HashSet<>();
        for (ChannelFeed feed : feedRepository.findByActiveTrue()) {
            long chat = feed.getChannelChatId();
            if (!handled.add(chat)) continue;
            try {
                Instant lastPost = offerRepository.findMaxPostedAtByChannelChatId(chat);
                if (lastPost != null && lastPost.isAfter(now.minus(minPostInterval))) continue;
                publishBurst(feed, cutoff, minCreated);
            } catch (Exception e) {
                log.error("Failed to publish feed {} to channel {}", feed.getId(), chat, e);
            }
        }
    }

    private void publishBurst(ChannelFeed feed, Instant cutoff, Instant minCreated) {
        List<FeedOffer> due = offerRepository.findDueOwnerOffers(
                feed.getId(), AgencyDetector.Verdict.OWNER.name(), cutoff, minCreated);
        boolean alreadyNotified = false;
        for (FeedOffer offer : due) {
            refreshOfferIfRentImplausible(offer);
            boolean silent = alreadyNotified || silentNow(Instant.now());
            try {
                send(feed, offer, silent);
            } catch (Exception e) {
                log.warn("Failed to post offer {} to channel {}", offer.getId(), feed.getChannelChatId(), e);
                continue;
            }
            alreadyNotified = alreadyNotified || !silent;
            offer.setPostedAt(Instant.now());
            offerRepository.save(offer);
        }
    }

    private void send(ChannelFeed feed, FeedOffer offer, boolean silent) throws Exception {
        Channel channel = channelRepository.findById(feed.getChannelChatId()).orElse(null);
        String text = buildText(feed, offer, channel);
        if (offer.getImageUrl() != null && trySendPhoto(feed, offer, text, silent)) {
            return;
        }
        telegramClient.execute(SendMessage.builder()
                .chatId(feed.getChannelChatId())
                .text(text)
                .disableNotification(silent)
                .build());
    }

    /** True when the given instant falls in the Warsaw-local night window, so posts should be silent. */
    boolean silentNow(Instant now) {
        return withinWindow(now.atZone(POST_ZONE).getHour(), silentFromHour, silentToHour);
    }

    /** Whether {@code hour} is inside [from, to), wrapping past midnight when from &gt; to; from==to is off. */
    static boolean withinWindow(int hour, int from, int to) {
        if (from == to) return false;
        return from < to ? hour >= from && hour < to : hour >= from || hour < to;
    }

    /**
     * Attempts a photo post. Returns {@code false} — signalling a text-only fallback — when Telegram
     * rejects the image itself with a 400 (e.g. the listing's image URL is dead or serves a web page,
     * not an image: "wrong type of the web page content"). That failure is permanent for this offer,
     * so retrying the photo would stall every owner offer queued behind it. Transient failures
     * (network, 5xx, rate limits) propagate so the offer simply retries on the next tick.
     */
    private boolean trySendPhoto(ChannelFeed feed, FeedOffer offer, String text, boolean silent) throws Exception {
        try {
            telegramClient.execute(SendPhoto.builder()
                    .chatId(feed.getChannelChatId())
                    .photo(new InputFile(offer.getImageUrl()))
                    .caption(StringUtils.abbreviate(text, 1024))
                    .disableNotification(silent)
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

    String buildText(ChannelFeed feed, FeedOffer offer, Channel channel) {
        StringBuilder sb = new StringBuilder();
        sb.append("🏠 ").append(cleanTitle(offer.getTitle())).append('\n');

        if (offer.getPrice() != null) {
            // the true monthly total leads (it is also what the score compares); breakdown in parens
            sb.append("💰 ").append(formatAmount(totalPrice(offer))).append(' ').append(displayCurrency(offer));
            BigDecimal czynsz = trustedExtraRent(offer);
            if (czynsz != null) {
                sb.append(" (").append(formatAmount(offer.getPrice()))
                        .append(" + ").append(formatAmount(czynsz)).append(" czynsz)");
            }
            scoreLine(feed, offer).ifPresent(line -> sb.append('\n').append(line));
            sb.append('\n');
        }
        if (offer.getAreaM2() != null) {
            sb.append("📐 ").append(offer.getAreaM2().stripTrailingZeros().toPlainString()).append(" m²");
            if (offer.getRooms() != null) sb.append(" · 🚪 ").append(offer.getRooms());
            if (offer.getLocation() != null) sb.append(" · 📍 ").append(offer.getLocation());
            sb.append('\n');
        } else if (offer.getLocation() != null) {
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

    /**
     * A price-context line built from same-feed comparables, narrowed as tightly as the sample
     * allows: same district and room count first, then same district, then the whole feed. The
     * first level with enough listings wins. The tight level compares monthly totals (a meaningful
     * median within one room-count segment); coarser levels mix room counts, so they compare
     * per-m² instead.
     */
    private Optional<String> scoreLine(ChannelFeed feed, FeedOffer offer) {
        if (offer.getAreaM2() == null || offer.getAreaM2().signum() <= 0) return Optional.empty();
        BigDecimal total = totalPrice(offer);
        List<FeedOffer> pool = offerRepository.findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(
                feed.getId(), Instant.now().minus(COMPARABLES_WINDOW));

        if (offer.getLocation() != null && offer.getRooms() != null) {
            Optional<RentalScorer.Score> s = RentalScorer.score(total, offer.getAreaM2(),
                    comps(pool, offer, o -> Objects.equals(o.getLocation(), offer.getLocation())
                            && Objects.equals(o.getRooms(), offer.getRooms())),
                    DISTRICT_ROOMS_MIN_SAMPLE);
            if (s.isPresent()) return Optional.of(renderSegment(offer, s.get()));
        }
        if (offer.getLocation() != null) {
            Optional<RentalScorer.Score> s = RentalScorer.score(total, offer.getAreaM2(),
                    comps(pool, offer, o -> Objects.equals(o.getLocation(), offer.getLocation())),
                    RentalScorer.MIN_SAMPLE);
            if (s.isPresent()) return Optional.of(renderPerM2(offer, s.get()));
        }
        return RentalScorer.score(total, offer.getAreaM2(), comps(pool, offer, o -> true),
                        RentalScorer.MIN_SAMPLE)
                .map(s -> renderPerM2(offer, s));
    }

    /** Comparables in {@code pool} matching {@code filter}, excluding the offer itself. */
    private static List<RentalScorer.Comp> comps(List<FeedOffer> pool, FeedOffer offer,
                                                 java.util.function.Predicate<FeedOffer> filter) {
        List<RentalScorer.Comp> out = new ArrayList<>();
        for (FeedOffer o : pool) {
            if (o.getId().equals(offer.getId()) || o.getAreaM2() == null || o.getAreaM2().signum() <= 0) {
                continue;
            }
            if (filter.test(o)) out.add(new RentalScorer.Comp(totalPrice(o), o.getAreaM2()));
        }
        return out;
    }

    /**
     * Tight district+rooms hit: two comparisons, each with its own verdict. Totals first — people
     * shop by room count, so "what does a 2-pok here cost" leads — then per-m², which stays fair
     * to flats larger or smaller than the segment's typical size.
     */
    private String renderSegment(FeedOffer offer, RentalScorer.Score s) {
        if (s.medianTotal().signum() <= 0) return renderPerM2(offer, s);
        String cur = displayCurrency(offer);
        BigDecimal total = totalPrice(offer);
        int totalPct = pctDiff(total, s.medianTotal());
        return String.format("📊 %s %s · %s vs %s %s · n=%d\n📊 %s %s · %s vs %s %s/m²",
                signedPct(totalPct), verdictDot(totalPct),
                formatAmount(total), formatAmount(s.medianTotal()), cur, s.sampleSize(),
                signedPct(s.diffPct()), verdictDot(s.diffPct()),
                formatAmount(s.pricePerM2()), formatAmount(s.medianPerM2()), cur);
    }

    /**
     * Fallback levels mix room counts, so the comparison stays per-m²; the ~ figure translates the
     * median back into a market estimate for this flat's area, directly comparable to the 💰 total.
     */
    private String renderPerM2(FeedOffer offer, RentalScorer.Score s) {
        String cur = displayCurrency(offer);
        BigDecimal estimate = s.medianPerM2().multiply(offer.getAreaM2());
        return String.format("📊 %s %s · %s vs %s %s/m² · ~%s %s · n=%d",
                signedPct(s.diffPct()), verdictDot(s.diffPct()),
                formatAmount(s.pricePerM2()), formatAmount(s.medianPerM2()), cur,
                formatAmount(estimate), cur, s.sampleSize());
    }

    private static int pctDiff(BigDecimal actual, BigDecimal median) {
        return actual.subtract(median).multiply(BigDecimal.valueOf(100))
                .divide(median, 0, RoundingMode.HALF_UP).intValue();
    }

    private static String signedPct(int pct) {
        String sign = pct > 0 ? "+" : pct < 0 ? "−" : "±";
        return sign + Math.abs(pct) + "%";
    }

    /** Instant verdict, language-free: 🟢 at −10% or better, 🔴 at +10% or worse, 🟡 between. */
    private static String verdictDot(int pct) {
        return pct <= -10 ? "🟢" : pct >= 10 ? "🔴" : "🟡";
    }

    /** Scraped titles carry stray whitespace, e.g. "Bemowo , pełne" — tidy before display. */
    private static String cleanTitle(String title) {
        return StringUtils.defaultString(title).replaceAll("\\s+,", ",").replaceAll("\\s{2,}", " ").trim();
    }

    private static BigDecimal totalPrice(FeedOffer offer) {
        BigDecimal extra = trustedExtraRent(offer);
        return extra == null ? offer.getPrice() : offer.getPrice().add(extra);
    }

    private static BigDecimal trustedExtraRent(FeedOffer offer) {
        BigDecimal extra = offer.getExtraRent();
        if (extra == null || extra.signum() <= 0 || offer.getPrice() == null) return null;
        return extra.compareTo(offer.getPrice()) < 0 ? extra : null;
    }

    private static boolean hasImplausibleExtraRent(FeedOffer offer) {
        BigDecimal extra = offer.getExtraRent();
        return extra != null && extra.signum() > 0
                && (offer.getPrice() == null || extra.compareTo(offer.getPrice()) >= 0);
    }

    private void refreshOfferIfRentImplausible(FeedOffer offer) {
        if (!hasImplausibleExtraRent(offer)) return;
        try {
            ListingEnricher.Enriched fresh = enricher.enrich(offer.getUrl());
            if (fresh.price() == null) return;
            offer.setPrice(fresh.price());
            if (fresh.currency() != null) offer.setCurrency(fresh.currency());
            offer.setExtraRent(fresh.extraRent());
            offerRepository.save(offer);
        } catch (Exception e) {
            log.warn("Failed to refresh czynsz for offer {}: {}", offer.getId(), e.toString());
        }
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
