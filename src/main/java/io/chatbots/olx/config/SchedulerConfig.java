package io.chatbots.olx.config;

import io.chatbots.olx.OlxTelegramBot;
import io.chatbots.olx.channel.ChannelFeedPoller;
import io.chatbots.olx.channel.ChannelPublisher;
import io.chatbots.olx.checker.RegressionChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.TimeUnit;

@Configuration
@RequiredArgsConstructor
@EnableScheduling
public class SchedulerConfig {
    private final OlxTelegramBot olxTelegramBot;
    private final RegressionChecker regressionChecker;
    private final ChannelFeedPoller channelFeedPoller;
    private final ChannelPublisher channelPublisher;

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    public void scheduleSending() {
        olxTelegramBot.notifySubscribedChats();
    }

    @Scheduled(fixedDelay = 4, timeUnit = TimeUnit.HOURS)
    public void scheduleChecker() {
        regressionChecker.checkSitesForRegression();
    }

    @Scheduled(fixedDelayString = "${channel.poll-interval-ms:600000}")
    public void scheduleChannelFeedPoll() {
        channelFeedPoller.pollAll();
    }

    @Scheduled(fixedDelayString = "${channel.publish-interval-ms:300000}")
    public void scheduleChannelPublish() {
        channelPublisher.publishDue();
    }

}
