package io.chatbots.olx.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Alerts when the rental feed stops producing listings. A stalled pipeline is silent by nature —
 * nothing throws, nothing retries, the channel simply goes quiet — so the only way to notice is to
 * watch the clock on the newest row. An August 2026 stall ran four days before anyone spotted it,
 * and cost the one subscriber who was actively using the bot.
 */
@Slf4j
@RequiredArgsConstructor
public class IngestionWatchdog {

    private final FeedOfferRepository offerRepository;
    private final Consumer<String> alerter;
    private final Duration staleAfter;
    /** Re-alerts at this interval while the stall persists, so a long outage keeps nagging. */
    private final Duration repeatEvery;

    private Instant lastAlertAt;

    public void checkFreshness() {
        Instant newest = offerRepository.findMaxFirstSeen();
        Instant now = Instant.now();
        if (newest == null) return;

        Duration silentFor = Duration.between(newest, now);
        if (silentFor.compareTo(staleAfter) < 0) {
            if (lastAlertAt != null) {
                log.info("Ingestion recovered; newest listing is {} old", silentFor);
            }
            lastAlertAt = null;
            return;
        }
        if (lastAlertAt != null && Duration.between(lastAlertAt, now).compareTo(repeatEvery) < 0) {
            return;
        }
        lastAlertAt = now;
        String message = "🚨 no new listings for " + humanize(silentFor)
                + " (newest: " + newest + "). Feed polling is probably stalled.";
        log.warn("Ingestion stalled: no new listings for {}", silentFor);
        alerter.accept(message);
    }

    static String humanize(Duration duration) {
        long hours = duration.toHours();
        if (hours < 1) return duration.toMinutes() + "m";
        return hours < 48 ? hours + "h" : duration.toDays() + "d";
    }
}
