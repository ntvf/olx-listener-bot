package io.chatbots.olx.furniture.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single used-IKEA listing seen in a furniture feed — the posting queue and the price
 * history for the model median in one table (mirrors {@code FeedOffer} for rentals).
 */
@Entity
@Table(name = "furniture_offers")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FurnitureOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "feed_id", nullable = false)
    private long feedId;

    @Column(name = "offer_hash", nullable = false, length = 64)
    private String offerHash;

    @Column(length = 2048)
    private String url;

    @Column(length = 512)
    private String title;

    private BigDecimal price;

    @Column(length = 8)
    private String currency;

    /** The IKEA model this listing was bucketed under; drives the median segment. */
    @Column(length = 32)
    private String model;

    /**
     * True for parts/accessories (a door, drawer, cover…) or sub-floor prices. Kept out of the
     * model median and never posted, but stored so it is not re-enriched on every poll.
     */
    @Column(nullable = false)
    private boolean part;

    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "listing_created_at")
    private Instant listingCreatedAt;

    /** The listing's real publish time (from the OLX card), or first_seen when unknown; posts schedule from this. */
    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "posted_at")
    private Instant postedAt;
}
