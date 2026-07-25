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
    private final FurniturePhotoEnricher photoEnricher;
    private final FurnitureCatalogScraper catalogScraper;

    @Value("${furniture.poll-min-seconds:300}")
    private long pollMinSeconds;
    @Value("${furniture.poll-max-seconds:420}")
    private long pollMaxSeconds;

    @Scheduled(fixedDelayString = "${furniture.publish-interval-ms:300000}")
    public void publish() {
        publisher.publishDue();
    }

    /**
     * Kicks the photo enricher; it hands off to its own thread and returns immediately, so this
     * never delays polling or publishing. No-op unless {@code furniture.photo-ai.enabled}.
     */
    @Scheduled(fixedDelayString = "${furniture.photo-ai.interval-ms:600000}")
    public void enrichPhotos() {
        photoEnricher.tick();
    }

    /**
     * Checks the IKEA catalog daily and re-scrapes only when it is older than {@code max-age-days}
     * (≈1.5 months) — see {@link FurnitureCatalogScraper#refreshIfStale()}. Cheap when fresh (one
     * timestamp query); the heavy crawl runs on the scraper's own thread, so this never blocks.
     */
    @Scheduled(fixedDelayString = "${furniture.catalog.check-interval-ms:86400000}")
    public void refreshCatalog() {
        catalogScraper.refreshIfStale();
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
