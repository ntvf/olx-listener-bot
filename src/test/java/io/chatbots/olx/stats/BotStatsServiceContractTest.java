package io.chatbots.olx.stats;

import io.chatbots.olx.storage.ListenerStorage;
import io.chatbots.olx.storage.entity.Listener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for BotStatsService. Any implementation must satisfy all these
 * assertions. Extend this class and provide both a stats service and a storage
 * backed by the same data source.
 */
abstract class BotStatsServiceContractTest {

    private BotStatsService statsService;
    private ListenerStorage storage;

    /**
     * Returns a fresh stats service and a corresponding storage that share the same
     * underlying data. Both must be reset before each test.
     */
    protected abstract BotStatsService newStatsService();

    protected abstract ListenerStorage newStorage();

    @BeforeEach
    void init() {
        statsService = newStatsService();
        storage = newStorage();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    protected Listener activeListener(long userId, long chatId, String langCode) {
        return Listener.builder()
                .userId(userId)
                .chatId(chatId)
                .url("https://www.olx.pl/oferty/q-test/")
                .userFirstName("User" + userId)
                .userName("user" + userId)
                .userLanguageCode(langCode)
                .updated(new Date())
                .active(true)
                .build();
    }

    protected Listener inactiveListener(long userId, long chatId, String langCode) {
        Listener l = activeListener(userId, chatId, langCode);
        l.setActive(false);
        return l;
    }

    // ── empty state ───────────────────────────────────────────────────────────

    @Test
    void emptyStorage_allCountsAreZero() {
        BotStats stats = statsService.getBotStats();

        assertThat(stats.getAllListenersCount()).isZero();
        assertThat(stats.getActiveListenersCount()).isZero();
        assertThat(stats.getAllUsersCount()).isZero();
        assertThat(stats.getActiveUsersCount()).isZero();
    }

    @Test
    void emptyStorage_localeStatsAreEmpty() {
        BotStats stats = statsService.getBotStats();

        assertThat(stats.getAllUsersLocales().getLocalesCount()).isEmpty();
        assertThat(stats.getActiveUsersLocales().getLocalesCount()).isEmpty();
    }

    // ── listeners count ───────────────────────────────────────────────────────

    @Test
    void singleActiveListener_countsAreOne() {
        storage.saveListener(activeListener(1L, 100L, "en"));

        BotStats stats = statsService.getBotStats();

        assertThat(stats.getAllListenersCount()).isEqualTo(1);
        assertThat(stats.getActiveListenersCount()).isEqualTo(1);
    }

    @Test
    void inactiveListener_countsAllButNotActive() {
        storage.saveListener(inactiveListener(1L, 100L, "en"));

        BotStats stats = statsService.getBotStats();

        assertThat(stats.getAllListenersCount()).isEqualTo(1);
        assertThat(stats.getActiveListenersCount()).isZero();
    }

    @Test
    void mixedListeners_activeAndTotalCountsAreCorrect() {
        storage.saveListener(activeListener(1L, 100L, "en"));
        storage.saveListener(activeListener(2L, 200L, "ru"));
        storage.saveListener(inactiveListener(3L, 300L, "uk"));

        BotStats stats = statsService.getBotStats();

        assertThat(stats.getAllListenersCount()).isEqualTo(3);
        assertThat(stats.getActiveListenersCount()).isEqualTo(2);
    }

    // ── user deduplication ────────────────────────────────────────────────────

    @Test
    void sameUserMultipleActiveListeners_countedOnceInUsers() {
        storage.saveListener(activeListener(1L, 100L, "en"));
        storage.saveListener(activeListener(1L, 100L, "en"));

        BotStats stats = statsService.getBotStats();

        assertThat(stats.getAllListenersCount()).isEqualTo(2);
        assertThat(stats.getAllUsersCount()).isEqualTo(1);
        assertThat(stats.getActiveUsersCount()).isEqualTo(1);
    }

    @Test
    void differentUsers_eachCountedOnce() {
        storage.saveListener(activeListener(1L, 100L, "en"));
        storage.saveListener(activeListener(2L, 200L, "en"));
        storage.saveListener(activeListener(3L, 300L, "en"));

        BotStats stats = statsService.getBotStats();

        assertThat(stats.getAllUsersCount()).isEqualTo(3);
        assertThat(stats.getActiveUsersCount()).isEqualTo(3);
    }

    @Test
    void inactiveUserNotCountedInActiveUsers() {
        storage.saveListener(activeListener(1L, 100L, "en"));
        storage.saveListener(inactiveListener(2L, 200L, "en"));

        BotStats stats = statsService.getBotStats();

        assertThat(stats.getAllUsersCount()).isEqualTo(2);
        assertThat(stats.getActiveUsersCount()).isEqualTo(1);
    }

    // ── locale stats ──────────────────────────────────────────────────────────

    @Test
    void localeStats_groupsByLanguageCode() {
        storage.saveListener(activeListener(1L, 100L, "en"));
        storage.saveListener(activeListener(2L, 200L, "en"));
        storage.saveListener(activeListener(3L, 300L, "uk"));

        BotStats stats = statsService.getBotStats();

        assertThat(stats.getActiveUsersLocales().getLocalesCount())
                .containsEntry("en", 2)
                .containsEntry("uk", 1);
    }

    @Test
    void localeStats_inactiveExcludedFromActiveLocales() {
        storage.saveListener(activeListener(1L, 100L, "en"));
        storage.saveListener(inactiveListener(2L, 200L, "ru"));

        BotStats stats = statsService.getBotStats();

        assertThat(stats.getActiveUsersLocales().getLocalesCount())
                .containsOnlyKeys("en");
        assertThat(stats.getAllUsersLocales().getLocalesCount())
                .containsKeys("en", "ru");
    }

    @Test
    void localeStats_sameUserCountedOncePerLanguage() {
        // user 1 has three active listeners all in "en"
        storage.saveListener(activeListener(1L, 100L, "en"));
        storage.saveListener(activeListener(1L, 100L, "en"));
        storage.saveListener(activeListener(1L, 100L, "en"));

        BotStats stats = statsService.getBotStats();

        assertThat(stats.getActiveUsersLocales().getLocalesCount())
                .containsEntry("en", 1);
    }
}
