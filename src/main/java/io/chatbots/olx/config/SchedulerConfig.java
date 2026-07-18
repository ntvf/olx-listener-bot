package io.chatbots.olx.config;

import io.chatbots.olx.OlxTelegramBot;
import io.chatbots.olx.channel.ChannelFeedPoller;
import io.chatbots.olx.channel.ChannelPublisher;
import io.chatbots.olx.checker.RegressionChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Configuration
@RequiredArgsConstructor
@EnableScheduling
public class SchedulerConfig implements SchedulingConfigurer {
    private final OlxTelegramBot olxTelegramBot;
    private final RegressionChecker regressionChecker;
    private final ChannelFeedPoller channelFeedPoller;
    private final ChannelPublisher channelPublisher;

    /** Feed scrape spacing is randomized within this range so OLX sees no fixed-cadence bursts. */
    @Value("${channel.poll-min-seconds:300}")
    private long pollMinSeconds;
    @Value("${channel.poll-max-seconds:420}")
    private long pollMaxSeconds;

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    public void scheduleSending() {
        olxTelegramBot.notifySubscribedChats();
    }

    @Scheduled(fixedDelay = 4, timeUnit = TimeUnit.HOURS)
    public void scheduleChecker() {
        regressionChecker.checkSitesForRegression();
    }

    @Scheduled(fixedDelayString = "${channel.publish-interval-ms:300000}")
    public void scheduleChannelPublish() {
        channelPublisher.publishDue();
    }

    /**
     * Scrapes each feed on a randomized interval (default 5–7 min) rather than a fixed delay, so the
     * requests don't hit OLX on a predictable clock that anti-bot systems flag as a burst pattern.
     * The next run is scheduled a random gap after the previous one completes.
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(channelFeedPoller::pollAll, this::nextPollTime);
    }

    private Instant nextPollTime(org.springframework.scheduling.TriggerContext context) {
        Instant last = context.lastCompletion();
        Instant base = last != null ? last : Instant.now();
        long gap = ThreadLocalRandom.current().nextLong(pollMinSeconds, pollMaxSeconds + 1);
        return base.plusSeconds(gap);
    }
}
