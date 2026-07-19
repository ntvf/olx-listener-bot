package io.chatbots.olx.config;

import io.chatbots.olx.channel.ChannelAdminService;
import io.chatbots.olx.channel.ChannelFeedPoller;
import io.chatbots.olx.channel.ChannelFeedRepository;
import io.chatbots.olx.channel.ChannelPublisher;
import io.chatbots.olx.channel.ChannelRepository;
import io.chatbots.olx.channel.FeedOfferRepository;
import io.chatbots.olx.channel.ListingEnricher;
import io.chatbots.olx.grabber.OlxGrabber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;

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
                                               ListingEnricher enricher,
                                               @Value("${channel.max-listing-age-hours:48}") long maxListingAgeHours) {
        return new ChannelFeedPoller(feedRepository, offerRepository, grabber, enricher,
                Duration.ofHours(maxListingAgeHours));
    }

    @Bean
    public ChannelPublisher channelPublisher(ChannelFeedRepository feedRepository,
                                             FeedOfferRepository offerRepository,
                                             ChannelRepository channelRepository,
                                             TelegramClient telegramClient,
                                             ListingEnricher enricher,
                                             @Value("${channel.post-delay-minutes:60}") long postDelayMinutes,
                                             @Value("${channel.min-post-interval-minutes:60}") long minPostIntervalMinutes,
                                             @Value("${channel.silent-from-hour:22}") int silentFromHour,
                                             @Value("${channel.silent-to-hour:8}") int silentToHour) {
        return new ChannelPublisher(feedRepository, offerRepository, channelRepository, telegramClient,
                enricher, Duration.ofMinutes(postDelayMinutes), Duration.ofMinutes(minPostIntervalMinutes),
                silentFromHour, silentToHour);
    }

    @Bean
    public ChannelAdminService channelAdminService(ChannelRepository channelRepository,
                                                   ChannelFeedRepository feedRepository,
                                                   OlxGrabber grabber) {
        return new ChannelAdminService(channelRepository, feedRepository, grabber);
    }
}
