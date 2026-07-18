package io.chatbots.olx.channel.entity;

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

@Entity
@Table(name = "feed_offers")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FeedOffer {

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

    @Column(name = "extra_rent")
    private BigDecimal extraRent;

    @Column(name = "area_m2")
    private BigDecimal areaM2;

    private Integer rooms;

    private String location;

    @Column(name = "seller_id", length = 128)
    private String sellerId;

    @Column(name = "seller_business")
    private Boolean sellerBusiness;

    /** Advertiser phone, when the listing exposes one (currently only Otodom does). */
    @Column(length = 32)
    private String phone;

    /** Advertiser / agency display name, when available (Otodom {@code owner.name}). */
    @Column(name = "advertiser_name", length = 255)
    private String advertiserName;

    @Column(length = 16)
    private String verdict;

    /** True when the listing advertises itself as owner-direct ("bezpośrednio", "od właściciela"). */
    @Column(nullable = false)
    private boolean direct;

    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "posted_at")
    private Instant postedAt;
}
