package io.chatbots.olx.channel;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelFeedPollerTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private final Instant firstSeen = Instant.parse("2026-07-18T18:00:00Z");

    @Test
    void preciseCardTimeIsZonedFromWarsaw() {
        // "Dzisiaj o 19:16" -> 19:16 Warsaw (summer, UTC+2) = 17:16Z, not first_seen
        LocalDateTime card = LocalDateTime.of(2026, 7, 18, 19, 16);
        assertEquals(Instant.parse("2026-07-18T17:16:00Z"),
                ChannelFeedPoller.publishInstant(card, firstSeen));
    }

    @Test
    void dateOnlyCardFallsBackToFirstSeen() {
        // older listings show only a date -> parsed to midnight -> unreliable, use discovery time
        LocalDateTime midnight = LocalDate.of(2026, 7, 15).atStartOfDay();
        assertEquals(firstSeen, ChannelFeedPoller.publishInstant(midnight, firstSeen));
    }

    @Test
    void nullCardFallsBackToFirstSeen() {
        assertEquals(firstSeen, ChannelFeedPoller.publishInstant(null, firstSeen));
    }

    @Test
    void warsawZoneIsApplied() {
        // sanity: the same wall clock zoned to Warsaw differs from treating it as UTC
        LocalDateTime card = LocalDateTime.of(2026, 7, 18, 12, 0);
        assertEquals(card.atZone(WARSAW).toInstant(),
                ChannelFeedPoller.publishInstant(card, firstSeen));
    }
}
