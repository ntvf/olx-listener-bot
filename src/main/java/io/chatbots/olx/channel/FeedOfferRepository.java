package io.chatbots.olx.channel;

import io.chatbots.olx.channel.entity.FeedOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface FeedOfferRepository extends JpaRepository<FeedOffer, Long> {

    @Query("SELECT o.offerHash FROM FeedOffer o WHERE o.feedId = :feedId")
    Set<String> findHashesByFeedId(@Param("feedId") long feedId);

    @Query("SELECT o FROM FeedOffer o WHERE o.feedId = :feedId AND o.postedAt IS NULL "
            + "AND o.verdict = :verdict AND o.direct = true "
            + "AND COALESCE(o.listingCreatedAt, o.publishedAt) <= :cutoff "
            + "AND (o.listingCreatedAt IS NULL OR o.listingCreatedAt >= :minCreated) "
            + "ORDER BY COALESCE(o.listingCreatedAt, o.publishedAt) ASC")
    List<FeedOffer> findDueOwnerOffers(@Param("feedId") long feedId,
                                       @Param("verdict") String verdict,
                                       @Param("cutoff") Instant cutoff,
                                       @Param("minCreated") Instant minCreated);

    @Query("SELECT MAX(o.postedAt) FROM FeedOffer o, ChannelFeed f "
            + "WHERE o.feedId = f.id AND f.channelChatId = :chatId")
    Instant findMaxPostedAtByChannelChatId(@Param("chatId") long chatId);

    /**
     * True when a listing with the same content fingerprint (price, area, rooms, location, title)
     * was already posted to this feed within the window. Catches the same flat re-listed under fresh
     * throwaway accounts — different ad id, seller and re-uploaded photos, but identical specs+title.
     */
    @Query("SELECT COUNT(o) > 0 FROM FeedOffer o WHERE o.feedId = :feedId AND o.id <> :excludeId "
            + "AND o.postedAt IS NOT NULL AND o.postedAt >= :since "
            + "AND o.price = :price AND o.areaM2 = :area AND o.rooms = :rooms "
            + "AND o.location = :location AND LOWER(TRIM(o.title)) = LOWER(TRIM(:title))")
    boolean existsPostedDuplicate(@Param("feedId") long feedId,
                                  @Param("excludeId") long excludeId,
                                  @Param("since") Instant since,
                                  @Param("price") java.math.BigDecimal price,
                                  @Param("area") java.math.BigDecimal area,
                                  @Param("rooms") Integer rooms,
                                  @Param("location") String location,
                                  @Param("title") String title);

    List<FeedOffer> findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(
            long feedId, Instant since);

    long countBySellerIdAndFirstSeenAfter(String sellerId, Instant since);

    long countBySellerId(String sellerId);

    @Query("SELECT MIN(o.firstSeen) FROM FeedOffer o WHERE o.sellerId = :sellerId")
    Instant findEarliestFirstSeenBySellerId(@Param("sellerId") String sellerId);

    long countByPhoneAndFirstSeenAfter(String phone, Instant since);

    long countByPhone(String phone);

    @Query("SELECT MIN(o.firstSeen) FROM FeedOffer o WHERE o.phone = :phone")
    Instant findEarliestFirstSeenByPhone(@Param("phone") String phone);
}
