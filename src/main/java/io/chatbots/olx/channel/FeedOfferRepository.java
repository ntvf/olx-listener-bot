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

    List<FeedOffer> findByFeedIdAndPostedAtIsNullAndVerdictAndFirstSeenBeforeOrderByFirstSeenAsc(
            long feedId, String verdict, Instant cutoff);

    List<FeedOffer> findByFeedIdAndFirstSeenAfterAndPriceIsNotNullAndAreaM2IsNotNull(
            long feedId, Instant since);

    long countBySellerIdAndFirstSeenAfter(String sellerId, Instant since);

    long countBySellerId(String sellerId);

    @Query("SELECT MIN(o.firstSeen) FROM FeedOffer o WHERE o.sellerId = :sellerId")
    Instant findEarliestFirstSeenBySellerId(@Param("sellerId") String sellerId);
}
