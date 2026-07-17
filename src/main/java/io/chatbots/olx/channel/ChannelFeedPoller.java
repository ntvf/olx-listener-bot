package io.chatbots.olx.channel;

import io.chatbots.olx.channel.entity.ChannelFeed;
import io.chatbots.olx.channel.entity.FeedOffer;
import io.chatbots.olx.grabber.Offer;
import io.chatbots.olx.grabber.OlxGrabber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Polls every active channel feed, enriches unseen offers from their detail pages and
 * stores them in feed_offers — the posting queue and the ask-price history in one table.
 */
@Slf4j
@RequiredArgsConstructor
public class ChannelFeedPoller {

    static final Duration SELLER_ROTATION_WINDOW = Duration.ofDays(7);
    static final Duration SELLER_COUNT_WINDOW = Duration.ofDays(30);
    static final Duration SELLER_HISTORY_WINDOW = Duration.ofDays(90);
    private static final long DETAIL_FETCH_PAUSE_MS = 1500;

    private final ChannelFeedRepository feedRepository;
    private final FeedOfferRepository offerRepository;
    private final OlxGrabber grabber;
    private final ListingEnricher enricher;

    public void pollAll() {
        for (ChannelFeed feed : feedRepository.findByActiveTrue()) {
            try {
                pollFeed(feed);
            } catch (Exception e) {
                log.error("Failed to poll feed {} ({})", feed.getId(), feed.getFeedUrl(), e);
            }
        }
    }

    void pollFeed(ChannelFeed feed) {
        List<Offer> offers = grabber.getOffers(feed.getFeedUrl());
        Set<String> known = offerRepository.findHashesByFeedId(feed.getId());
        // first run seeds price history without flooding the channel with old listings
        boolean baseline = known.isEmpty();

        int stored = 0;
        for (Offer offer : offers) {
            String hash = DigestUtils.md5Hex(offer.getUrl());
            if (known.contains(hash)) continue;

            ListingEnricher.Enriched details = enricher.enrich(offer.getUrl());
            AgencyDetector.SellerActivity activity = sellerActivity(details.sellerId());
            AgencyDetector.Verdict verdict = AgencyDetector.classify(
                    offer.getName(), details.description(), details.sellerBusiness(), activity);

            Instant now = Instant.now();
            offerRepository.save(FeedOffer.builder()
                    .feedId(feed.getId())
                    .offerHash(hash)
                    .url(offer.getUrl())
                    .title(StringUtils.abbreviate(offer.getName(), 512))
                    .price(details.price())
                    .currency(details.currency())
                    .extraRent(details.extraRent())
                    .areaM2(details.areaM2())
                    .rooms(details.rooms())
                    .location(details.location())
                    .sellerId(details.sellerId())
                    .sellerBusiness(details.sellerBusiness())
                    .verdict(verdict.name())
                    .imageUrl(details.imageUrl())
                    .firstSeen(now)
                    .postedAt(baseline ? now : null)
                    .build());
            stored++;
            pause();
        }
        if (stored > 0) {
            log.info("Feed {}: stored {} new offers{}", feed.getId(), stored, baseline ? " (baseline)" : "");
        }
    }

    /**
     * How many listings this seller is already behind in our own history. Listings expire and
     * vanish from OLX, so a live "active listings" count understates agencies; our retained
     * feed_offers rows do not, which is why the counts come from the DB, not the site.
     */
    private AgencyDetector.SellerActivity sellerActivity(String sellerId) {
        if (sellerId == null) return AgencyDetector.SellerActivity.NONE;
        Instant now = Instant.now();
        long last7 = offerRepository.countBySellerIdAndFirstSeenAfter(
                sellerId, now.minus(SELLER_ROTATION_WINDOW));
        long last30 = offerRepository.countBySellerIdAndFirstSeenAfter(
                sellerId, now.minus(SELLER_COUNT_WINDOW));
        long last90 = offerRepository.countBySellerIdAndFirstSeenAfter(
                sellerId, now.minus(SELLER_HISTORY_WINDOW));
        long total = offerRepository.countBySellerId(sellerId);
        Instant earliest = offerRepository.findEarliestFirstSeenBySellerId(sellerId);
        Duration knownFor = earliest == null ? Duration.ZERO : Duration.between(earliest, now);
        return new AgencyDetector.SellerActivity(last7, last30, last90, knownFor, total);
    }

    private void pause() {
        try {
            Thread.sleep(DETAIL_FETCH_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
