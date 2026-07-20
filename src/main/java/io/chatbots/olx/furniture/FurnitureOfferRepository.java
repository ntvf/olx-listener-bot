package io.chatbots.olx.furniture;

import io.chatbots.olx.furniture.entity.FurnitureOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface FurnitureOfferRepository extends JpaRepository<FurnitureOffer, Long> {

    @Query("SELECT o.offerHash FROM FurnitureOffer o WHERE o.feedId = :feedId")
    Set<String> findHashesByFeedId(@Param("feedId") long feedId);

    /**
     * Whole-unit listings queued for posting: old enough (by real creation time), not stale,
     * not a part. The discount threshold is applied in Java once the model median is computed.
     */
    @Query("SELECT o FROM FurnitureOffer o WHERE o.feedId = :feedId AND o.postedAt IS NULL "
            + "AND o.part = false AND o.price IS NOT NULL "
            + "AND COALESCE(o.listingCreatedAt, o.publishedAt) <= :cutoff "
            + "AND (o.listingCreatedAt IS NULL OR o.listingCreatedAt >= :minCreated) "
            + "ORDER BY COALESCE(o.listingCreatedAt, o.publishedAt) ASC")
    List<FurnitureOffer> findDueOffers(@Param("feedId") long feedId,
                                       @Param("cutoff") Instant cutoff,
                                       @Param("minCreated") Instant minCreated);

    @Query("SELECT MAX(o.postedAt) FROM FurnitureOffer o, FurnitureFeed f "
            + "WHERE o.feedId = f.id AND f.channelChatId = :chatId")
    Instant findMaxPostedAtByChannelChatId(@Param("chatId") long chatId);

    /** Whole-unit comparables for the model median, drawn from the same feed's retained history. */
    List<FurnitureOffer> findByFeedIdAndPartFalseAndPriceIsNotNullAndFirstSeenAfter(
            long feedId, Instant since);
}
