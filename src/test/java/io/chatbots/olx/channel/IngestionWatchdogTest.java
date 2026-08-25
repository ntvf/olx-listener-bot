package io.chatbots.olx.channel;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestionWatchdogTest {

    private final FeedOfferRepository repository = mock(FeedOfferRepository.class);
    private final List<String> alerts = new ArrayList<>();

    private IngestionWatchdog watchdog(Duration staleAfter, Duration repeatEvery) {
        return new IngestionWatchdog(repository, alerts::add, staleAfter, repeatEvery);
    }

    @Test
    void staysQuietWhileListingsKeepArriving() {
        when(repository.findMaxFirstSeen()).thenReturn(Instant.now().minus(Duration.ofMinutes(20)));

        watchdog(Duration.ofHours(3), Duration.ofHours(12)).checkFreshness();

        assertThat(alerts).isEmpty();
    }

    @Test
    void alertsOnceWhenIngestionGoesStale() {
        when(repository.findMaxFirstSeen()).thenReturn(Instant.now().minus(Duration.ofHours(5)));
        IngestionWatchdog watchdog = watchdog(Duration.ofHours(3), Duration.ofHours(12));

        watchdog.checkFreshness();
        watchdog.checkFreshness();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst()).contains("no new listings for 5h");
    }

    /** The four-day August stall must keep nagging, not alert once and then go quiet again. */
    @Test
    void repeatsAlertAfterTheRepeatWindow() {
        when(repository.findMaxFirstSeen()).thenReturn(Instant.now().minus(Duration.ofHours(5)));
        IngestionWatchdog watchdog = watchdog(Duration.ofHours(3), Duration.ZERO);

        watchdog.checkFreshness();
        watchdog.checkFreshness();

        assertThat(alerts).hasSize(2);
    }

    @Test
    void alertsAgainAfterRecovery() {
        IngestionWatchdog watchdog = watchdog(Duration.ofHours(3), Duration.ofHours(12));
        when(repository.findMaxFirstSeen()).thenReturn(Instant.now().minus(Duration.ofHours(5)));
        watchdog.checkFreshness();

        when(repository.findMaxFirstSeen()).thenReturn(Instant.now());
        watchdog.checkFreshness();

        when(repository.findMaxFirstSeen()).thenReturn(Instant.now().minus(Duration.ofHours(5)));
        watchdog.checkFreshness();

        assertThat(alerts).hasSize(2);
    }

    @Test
    void doesNotAlertOnAnEmptyTable() {
        when(repository.findMaxFirstSeen()).thenReturn(null);

        watchdog(Duration.ofHours(3), Duration.ofHours(12)).checkFreshness();

        assertThat(alerts).isEmpty();
    }

    @Test
    void humanizesDurationsForTheAlertText() {
        assertThat(IngestionWatchdog.humanize(Duration.ofMinutes(45))).isEqualTo("45m");
        assertThat(IngestionWatchdog.humanize(Duration.ofHours(5))).isEqualTo("5h");
        assertThat(IngestionWatchdog.humanize(Duration.ofDays(4))).isEqualTo("4d");
    }
}
