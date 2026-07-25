package io.chatbots.olx.furniture;

import io.chatbots.olx.furniture.entity.FurnitureOffer;
import org.springframework.data.domain.Pageable;
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
     * Whole-unit listings queued for posting: old enough (by real creation time), not stale, not a
     * part, and <b>sized</b> — {@code variant IS NOT NULL}, i.e. a dimension was parsed from text or
     * recovered from the photo by AI. Unsized listings (dim_source none/none_ai) are held back so a
     * deal is never posted on a size-blurred median; they become postable only once the photo
     * enricher gives them a variant. The discount threshold is applied in Java once the median holds.
     */
    @Query("SELECT o FROM FurnitureOffer o WHERE o.feedId = :feedId AND o.postedAt IS NULL "
            + "AND o.part = false AND o.price IS NOT NULL AND o.variant IS NOT NULL "
            + "AND COALESCE(o.listingCreatedAt, o.publishedAt) <= :cutoff "
            + "AND (o.listingCreatedAt IS NULL OR o.listingCreatedAt >= :minCreated) "
            + "ORDER BY COALESCE(o.listingCreatedAt, o.publishedAt) ASC")
    List<FurnitureOffer> findDueOffers(@Param("feedId") long feedId,
                                       @Param("cutoff") Instant cutoff,
                                       @Param("minCreated") Instant minCreated);

    @Query("SELECT MAX(o.postedAt) FROM FurnitureOffer o, FurnitureFeed f "
            + "WHERE o.feedId = f.id AND f.channelChatId = :chatId")
    Instant findMaxPostedAtByChannelChatId(@Param("chatId") long chatId);

    /**
     * Whole-unit comparables for the model/variant median, from the same feed's retained history.
     * Only <b>sized</b> listings ({@code variant IS NOT NULL}) count, so an unsized-and-unscored
     * listing never skews another offer's median — the median is built from known-size units only.
     */
    @Query("SELECT o FROM FurnitureOffer o WHERE o.feedId = :feedId AND o.part = false "
            + "AND o.price IS NOT NULL AND o.variant IS NOT NULL AND o.firstSeen > :since")
    List<FurnitureOffer> findSizedComparables(@Param("feedId") long feedId, @Param("since") Instant since);

    /**
     * Whole units the text parser could not size ({@code dim_source = 'none'}), newest first, for the
     * AI-Mode photo enricher. Once tried they become {@code 'photo'} (found) or {@code 'none_ai'}
     * (no luck) so they are not re-queried; either way the offer can still post on the model median.
     */
    @Query("SELECT o FROM FurnitureOffer o WHERE o.part = false AND o.model IS NOT NULL "
            + "AND o.imageUrl IS NOT NULL AND o.price IS NOT NULL AND o.dimSource = 'none' "
            + "ORDER BY o.firstSeen DESC")
    List<FurnitureOffer> findPhotoCandidates(Pageable pageable);
}
