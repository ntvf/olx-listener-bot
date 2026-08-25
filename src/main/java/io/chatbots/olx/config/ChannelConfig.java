package io.chatbots.olx.config;

import io.chatbots.olx.channel.ChannelAdminService;
import io.chatbots.olx.channel.ChannelFeedPoller;
import io.chatbots.olx.channel.ChannelFeedRepository;
import io.chatbots.olx.channel.ChannelPublisher;
import io.chatbots.olx.channel.ChannelRepository;
import io.chatbots.olx.channel.FeedOfferRepository;
import io.chatbots.olx.channel.IngestionWatchdog;
import io.chatbots.olx.channel.ListingEnricher;
import io.chatbots.olx.grabber.OlxGrabber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;

@Slf4j
@Configuration
public class ChannelConfig {

    @Bean
    public ListingEnricher listingEnricher(
            @Value("${channel.harvest-phones:true}") boolean harvestPhones) {
        return new ListingEnricher(harvestPhones);
    }

    @Bean
    public ChannelFeedPoller channelFeedPoller(ChannelFeedRepository feedRepository,
                                               FeedOfferRepository offerRepository,
                                               OlxGrabber grabber,
                                               ListingEnricher enricher) {
        return new ChannelFeedPoller(feedRepository, offerRepository, grabber, enricher);
    }

    @Bean
    public ChannelPublisher channelPublisher(ChannelFeedRepository feedRepository,
                                             FeedOfferRepository offerRepository,
                                             ChannelRepository channelRepository,
                                             TelegramClient telegramClient,
                                             ListingEnricher enricher,
                                             @Value("${channel.post-delay-minutes:60}") long postDelayMinutes,
                                             @Value("${channel.min-post-interval-minutes:60}") long minPostIntervalMinutes,
                                             @Value("${channel.max-listing-age-hours:48}") long maxListingAgeHours,
                                             @Value("${channel.silent-from-hour:22}") int silentFromHour,
                                             @Value("${channel.silent-to-hour:8}") int silentToHour,
                                             @Value("${channel.duplicate-window-days:14}") long duplicateWindowDays) {
        return new ChannelPublisher(feedRepository, offerRepository, channelRepository, telegramClient,
                enricher, Duration.ofMinutes(postDelayMinutes), Duration.ofMinutes(minPostIntervalMinutes),
                Duration.ofHours(maxListingAgeHours), silentFromHour, silentToHour,
                Duration.ofDays(duplicateWindowDays));
    }

    /**
     * The alert goes to the configured admin id rather than the last {@code /stats} chat: a stall is
     * exactly the situation where nobody has run a command recently, so a remembered chat id is
     * usually null when it is needed most.
     */
    @Bean
    public IngestionWatchdog ingestionWatchdog(
            FeedOfferRepository offerRepository,
            TelegramClient telegramClient,
            @Value("${channel.admin-user-id:0}") long adminUserId,
            @Value("${channel.stale-after-hours:3}") long staleAfterHours,
            @Value("${channel.stale-repeat-hours:12}") long staleRepeatHours) {
        return new IngestionWatchdog(
                offerRepository,
                message -> sendAdminAlert(telegramClient, adminUserId, message),
                Duration.ofHours(staleAfterHours),
                Duration.ofHours(staleRepeatHours));
    }

    private static void sendAdminAlert(TelegramClient telegramClient, long adminUserId, String message) {
        if (adminUserId == 0) return;
        try {
            telegramClient.execute(SendMessage.builder().chatId(adminUserId).text(message).build());
        } catch (Exception e) {
            log.warn("Failed to send ingestion alert to admin {}", adminUserId, e);
        }
    }

    @Bean
    public ChannelAdminService channelAdminService(ChannelRepository channelRepository,
                                                   ChannelFeedRepository feedRepository,
                                                   OlxGrabber grabber) {
        return new ChannelAdminService(channelRepository, feedRepository, grabber);
    }
}
