package io.chatbots.olx.furniture;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives the furniture pipeline on its own cadence, independent of the rental scheduler. Feed
 * scraping runs on a randomized interval (so OLX sees no fixed-clock bursts); publishing runs on a
 * fixed tick and self-rate-limits to one burst per channel per interval inside the publisher.
 */
@Component
@RequiredArgsConstructor
public class FurnitureScheduler implements SchedulingConfigurer {

    private final FurnitureFeedPoller poller;
    private final FurniturePublisher publisher;

    @Value("${furniture.poll-min-seconds:300}")
    private long pollMinSeconds;
    @Value("${furniture.poll-max-seconds:420}")
    private long pollMaxSeconds;

    @Scheduled(fixedDelayString = "${furniture.publish-interval-ms:300000}")
    public void publish() {
        publisher.publishDue();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(poller::pollAll, this::nextPollTime);
    }

    private Instant nextPollTime(TriggerContext context) {
        Instant last = context.lastCompletion();
        Instant base = last != null ? last : Instant.now();
        long gap = ThreadLocalRandom.current().nextLong(pollMinSeconds, pollMaxSeconds + 1);
        return base.plusSeconds(gap);
    }
}
