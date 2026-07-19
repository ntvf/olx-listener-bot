package io.chatbots.olx;

import io.chatbots.olx.grabber.Offer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListenerFreshnessTest {

    private static final Duration MAX_AGE = Duration.ofHours(48);

    @Test
    void genuinelyNewListingIsFresh() {
        assertTrue(OlxTelegramBot.isFreshListing(
                Offer.builder().createdAt(Instant.now().minus(Duration.ofMinutes(5))).build(), MAX_AGE));
    }

    @Test
    void bumpedOldListingIsNotFresh() {
        assertFalse(OlxTelegramBot.isFreshListing(
                Offer.builder().createdAt(Instant.now().minus(Duration.ofDays(10))).build(), MAX_AGE));
    }

    @Test
    void missingCreationTimeFailsOpen() {
        assertTrue(OlxTelegramBot.isFreshListing(Offer.builder().createdAt(null).build(), MAX_AGE));
    }
}
