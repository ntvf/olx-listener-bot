package io.chatbots.olx.furniture;

import io.chatbots.olx.channel.ListingEnricher;
import io.chatbots.olx.furniture.entity.FurnitureFeed;
import io.chatbots.olx.furniture.entity.FurnitureOffer;
import io.chatbots.olx.grabber.Offer;
import io.chatbots.olx.grabber.OlxGrabber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * Polls every active furniture feed, enriches unseen listings from their detail pages, buckets
 * them by IKEA model and stores them in furniture_offers — the posting queue and the model-median
 * history in one table. The furniture counterpart of {@code ChannelFeedPoller}; no agency logic,
 * since the deal signal here is the price, not the seller.
 */
@Slf4j
@RequiredArgsConstructor
public class FurnitureFeedPoller {

    /** OLX card times ("Dzisiaj o 19:16") are Warsaw wall-clock; zone them here to a real instant. */
    private static final ZoneId LISTING_ZONE = ZoneId.of("Europe/Warsaw");

    private final FurnitureFeedRepository feedRepository;
    private final FurnitureOfferRepository offerRepository;
    private final OlxGrabber grabber;
    private final ListingEnricher enricher;
    private final long detailFetchPauseMs;

    public void pollAll() {
        for (FurnitureFeed feed : feedRepository.findByActiveTrue()) {
            try {
                pollFeed(feed);
            } catch (Exception e) {
                log.error("Failed to poll furniture feed {} ({})", feed.getId(), feed.getFeedUrl(), e);
            }
        }
    }

    void pollFeed(FurnitureFeed feed) {
        List<Offer> offers = grabber.getOffers(feed.getFeedUrl());
        Set<String> known = offerRepository.findHashesByFeedId(feed.getId());
        // first run seeds the median history without flooding the channel with old listings
        boolean baseline = known.isEmpty();

        int stored = 0;
        for (Offer offer : offers) {
            String hash = DigestUtils.md5Hex(offer.getUrl());
            if (known.contains(hash)) continue;

            ListingEnricher.Enriched details = enricher.enrich(offer.getUrl());
            // The feed is one broad "q=ikea" search; the model is detected from the title here.
            String model = FurnitureClassifier.modelFor(offer.getName(), null);
            // Not a recognisable whole IKEA unit — a non-IKEA collision, an unknown model, a part,
            // or a sub-floor price. Stored (so it is not re-enriched) but flagged out of the median.
            boolean part = model == null
                    || FurnitureClassifier.isPart(offer.getName(), details.price());

            Instant now = Instant.now();
            offerRepository.save(FurnitureOffer.builder()
                    .feedId(feed.getId())
                    .offerHash(hash)
                    .url(offer.getUrl())
                    .title(StringUtils.abbreviate(offer.getName(), 512))
                    .price(details.price())
                    .currency(details.currency())
                    .model(model)
                    .part(part)
                    .imageUrl(details.imageUrl())
                    .firstSeen(now)
                    .listingCreatedAt(details.createdAt())
                    .publishedAt(publishInstant(offer.getUpdatedAt(), now))
                    .postedAt(baseline ? now : null)
                    .build());
            stored++;
            pause();
        }
        if (stored > 0) {
            log.info("Furniture feed {}: stored {} new offers{}", feed.getId(), stored, baseline ? " (baseline)" : "");
        }
    }

    /**
     * The instant a listing was actually published, used to schedule its post. The OLX card time is
     * Warsaw wall-clock; a precise "today HH:mm" is zoned to a real instant, while a date-only card
     * (older listings, parsed to midnight) or an unparsed one falls back to first discovery.
     */
    static Instant publishInstant(LocalDateTime cardTime, Instant firstSeen) {
        if (cardTime == null || cardTime.toLocalTime().equals(LocalTime.MIDNIGHT)) return firstSeen;
        return cardTime.atZone(LISTING_ZONE).toInstant();
    }

    private void pause() {
        if (detailFetchPauseMs <= 0) return;
        try {
            Thread.sleep(detailFetchPauseMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
