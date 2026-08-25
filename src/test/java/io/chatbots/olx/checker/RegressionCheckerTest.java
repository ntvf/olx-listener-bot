package io.chatbots.olx.checker;

import io.chatbots.olx.grabber.Offer;
import io.chatbots.olx.grabber.OlxGrabber;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegressionCheckerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static Offer offer() {
        return Offer.builder().name("iPhone").url("https://site/d/item.html").build();
    }

    @Test
    void resultsAreKeyedByDomainNotFullUrl() {
        OlxGrabber grabber = mock(OlxGrabber.class);
        when(grabber.getOffers(anyString())).thenReturn(List.of(offer()));
        RegressionChecker checker = new RegressionChecker(grabber, TIMEOUT);

        checker.checkSitesForRegression();

        assertThat(checker.getLastResults().keySet())
                .contains("olx.ua", "bazaraki.com", "otodom.pl", "otomoto.pl")
                .allSatisfy(key -> assertThat(key).doesNotContain("http", "/"));
    }

    @Test
    void alertsWithDomainWhenParserStopsWorking() {
        OlxGrabber grabber = mock(OlxGrabber.class);
        RegressionChecker checker = new RegressionChecker(grabber, TIMEOUT);
        AtomicReference<Set<String>> alerted = new AtomicReference<>();
        checker.setRegressionListener(alerted::set);

        when(grabber.getOffers(anyString())).thenReturn(List.of(offer()));
        checker.checkSitesForRegression();
        assertThat(alerted.get()).isNull();

        String bazarakiUrl = "https://www.bazaraki.com/telephones/mobile-phones/?ordering=newest";
        when(grabber.getOffers(bazarakiUrl)).thenReturn(Collections.emptyList());
        checker.checkSitesForRegression();

        assertThat(alerted.get()).containsExactly("bazaraki.com");
    }

    /**
     * The outage this guards against: otomoto stopped responding mid-body and outlasted the parser's
     * own read timeout, holding the scheduler thread for hours and stopping every feed poll and
     * channel post. A hung site must cost one probe, not the run.
     */
    @Test
    void hungSiteIsAbandonedAndTheRestOfTheRunContinues() throws Exception {
        String otomotoUrl = "https://www.otomoto.pl/osobowe?search%5Border%5D=created_at_first%3Adesc";
        CountDownLatch released = new CountDownLatch(1);
        OlxGrabber grabber = mock(OlxGrabber.class);
        when(grabber.getOffers(anyString())).thenReturn(List.of(offer()));
        when(grabber.getOffers(otomotoUrl)).thenAnswer(invocation -> {
            released.await();
            return List.of(offer());
        });
        RegressionChecker checker = new RegressionChecker(grabber, Duration.ofMillis(200));

        try {
            checker.checkSitesForRegression();

            assertThat(checker.getLastResults())
                    .containsEntry("otomoto.pl", false)
                    .containsEntry("olx.ua", true)
                    .containsEntry("olx.pl", true);
        } finally {
            released.countDown();
        }
    }

    @Test
    void doesNotAlertWhenParserWasAlreadyBroken() {
        OlxGrabber grabber = mock(OlxGrabber.class);
        when(grabber.getOffers(anyString())).thenReturn(Collections.emptyList());
        RegressionChecker checker = new RegressionChecker(grabber, TIMEOUT);
        AtomicReference<Set<String>> alerted = new AtomicReference<>();
        checker.setRegressionListener(alerted::set);

        checker.checkSitesForRegression();
        checker.checkSitesForRegression();

        assertThat(alerted.get()).isNull();
    }
}
