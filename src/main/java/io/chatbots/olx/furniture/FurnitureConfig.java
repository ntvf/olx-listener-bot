package io.chatbots.olx.furniture;

import io.chatbots.olx.channel.ChannelRepository;
import io.chatbots.olx.channel.ListingEnricher;
import io.chatbots.olx.grabber.OlxGrabber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;

/**
 * Wires the isolated used-IKEA deal pipeline. Reuses the shared {@link OlxGrabber},
 * {@link ChannelRepository} and a phone-free {@link ListingEnricher}; everything else is furniture's
 * own. All tunables come from {@code furniture.*} properties so the rental channel's config is
 * untouched.
 */
@Configuration
public class FurnitureConfig {

    @Bean
    public FurnitureFeedPoller furnitureFeedPoller(FurnitureFeedRepository feedRepository,
                                                   FurnitureOfferRepository offerRepository,
                                                   OlxGrabber grabber,
                                                   @Value("${furniture.detail-fetch-pause-ms:1500}") long detailFetchPauseMs) {
        // Constructed inline (not a bean) so the rental channel's single ListingEnricher bean stays
        // unambiguous. Phone harvesting is a rental/agency concern the furniture pipeline never needs.
        return new FurnitureFeedPoller(feedRepository, offerRepository, grabber,
                new ListingEnricher(false), detailFetchPauseMs);
    }

    @Bean
    public FurniturePublisher furniturePublisher(FurnitureFeedRepository feedRepository,
                                                 FurnitureOfferRepository offerRepository,
                                                 ChannelRepository channelRepository,
                                                 TelegramClient telegramClient,
                                                 @Value("${furniture.post-delay-minutes:60}") long postDelayMinutes,
                                                 @Value("${furniture.min-post-interval-minutes:60}") long minPostIntervalMinutes,
                                                 @Value("${furniture.max-listing-age-hours:48}") long maxListingAgeHours,
                                                 @Value("${furniture.comparables-window-days:35}") long comparablesWindowDays,
                                                 @Value("${furniture.min-discount-pct:25}") int minDiscountPct,
                                                 @Value("${furniture.silent-from-hour:22}") int silentFromHour,
                                                 @Value("${furniture.silent-to-hour:8}") int silentToHour) {
        return new FurniturePublisher(feedRepository, offerRepository, channelRepository, telegramClient,
                Duration.ofMinutes(postDelayMinutes), Duration.ofMinutes(minPostIntervalMinutes),
                Duration.ofHours(maxListingAgeHours), Duration.ofDays(comparablesWindowDays),
                minDiscountPct, silentFromHour, silentToHour);
    }

    @Bean
    public FurnitureAdminService furnitureAdminService(ChannelRepository channelRepository,
                                                       FurnitureFeedRepository feedRepository,
                                                       OlxGrabber grabber) {
        return new FurnitureAdminService(channelRepository, feedRepository, grabber);
    }
}
