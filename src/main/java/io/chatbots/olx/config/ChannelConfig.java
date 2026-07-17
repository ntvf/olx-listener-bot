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
    public ListingEnricher listingEnricher() {
        return new ListingEnricher();
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
                                             @Value("${channel.post-delay-minutes:60}") long postDelayMinutes,
                                             @Value("${channel.max-posts-per-tick:5}") int maxPostsPerTick) {
        return new ChannelPublisher(feedRepository, offerRepository, channelRepository, telegramClient,
                Duration.ofMinutes(postDelayMinutes), maxPostsPerTick);
    }

    @Bean
    public ChannelAdminService channelAdminService(ChannelRepository channelRepository,
                                                   ChannelFeedRepository feedRepository,
                                                   OlxGrabber grabber) {
        return new ChannelAdminService(channelRepository, feedRepository, grabber);
    }
}
