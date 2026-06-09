package io.chatbots.olx.storage;

import io.chatbots.olx.storage.entity.Listener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for ListenerStorage. Any storage implementation (Mongo, Postgres, …)
 * must satisfy all these tests. Extend this class and provide {@link #newStorage()} to
 * plug in your implementation.
 */
abstract class ListenerStorageContractTest {

    private ListenerStorage storage;

    /**
     * Returns a fresh, empty storage instance before each test.
     * Implementations are responsible for clearing persistent state.
     */
    protected abstract ListenerStorage newStorage();

    @BeforeEach
    void initStorage() {
        storage = newStorage();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    protected Listener activeListener(long userId, long chatId) {
        return Listener.builder()
                .userId(userId)
                .chatId(chatId)
                .url("https://www.olx.pl/oferty/q-iphone/")
                .userFirstName("John")
                .userLastName("Doe")
                .userName("johndoe")
                .userLanguageCode("en")
                .updated(new Date())
                .active(true)
                .build();
    }

    protected Listener inactiveListener(long userId, long chatId) {
        Listener l = activeListener(userId, chatId);
        l.setActive(false);
        return l;
    }

    // ── saveListener ─────────────────────────────────────────────────────────

    @Test
    void saveListener_assignsIdOnCreate() {
        Listener listener = activeListener(1L, 100L);
        assertThat(listener.getId()).as("pre-condition: id must be null before save").isNull();

        Listener saved = storage.saveListener(listener);

        assertThat(saved.getId()).isNotNull().isPositive();
    }

    @Test
    void saveListener_stampsUpdatedTimestamp() {
        Listener listener = activeListener(1L, 100L);
        listener.setUpdated(null);

        Listener saved = storage.saveListener(listener);

        assertThat(saved.getUpdated()).isNotNull();
    }

    @Test
    void saveListener_updatesExistingRecord() {
        Listener saved = storage.saveListener(activeListener(1L, 100L));
        Long id = saved.getId();

        saved.setUrl("https://www.olx.ua/list/q-phone/");
        storage.saveListener(saved);

        List<Listener> result = storage.getChatListeners(100L);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(id);
        assertThat(result.get(0).getUrl()).isEqualTo("https://www.olx.ua/list/q-phone/");
    }

    @Test
    void saveListener_persistsAllFields() {
        Listener listener = activeListener(42L, 999L);
        listener.setUserFirstName("Alice");
        listener.setUserLastName("Smith");
        listener.setUserName("alice");
        listener.setUserLanguageCode("uk");
        listener.setUrl("https://www.olx.bg/ads/q-laptop/");

        storage.saveListener(listener);
        Listener loaded = storage.getChatListeners(999L).get(0);

        assertThat(loaded.getUserId()).isEqualTo(42L);
        assertThat(loaded.getChatId()).isEqualTo(999L);
        assertThat(loaded.getUserFirstName()).isEqualTo("Alice");
        assertThat(loaded.getUserLastName()).isEqualTo("Smith");
        assertThat(loaded.getUserName()).isEqualTo("alice");
        assertThat(loaded.getUserLanguageCode()).isEqualTo("uk");
        assertThat(loaded.getUrl()).isEqualTo("https://www.olx.bg/ads/q-laptop/");
        assertThat(loaded.isActive()).isTrue();
    }

    // ── getChatListeners ─────────────────────────────────────────────────────

    @Test
    void getChatListeners_returnsActiveForSpecifiedChat() {
        storage.saveListener(activeListener(1L, 100L));
        storage.saveListener(activeListener(2L, 100L));

        List<Listener> result = storage.getChatListeners(100L);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(Listener::isActive);
        assertThat(result).allMatch(l -> l.getChatId() == 100L);
    }

    @Test
    void getChatListeners_excludesInactiveListeners() {
        Listener active = storage.saveListener(activeListener(1L, 100L));
        storage.saveListener(inactiveListener(2L, 100L));

        List<Listener> result = storage.getChatListeners(100L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(active.getId());
    }

    @Test
    void getChatListeners_excludesOtherChats() {
        storage.saveListener(activeListener(1L, 100L));
        storage.saveListener(activeListener(2L, 200L));

        assertThat(storage.getChatListeners(100L)).hasSize(1);
        assertThat(storage.getChatListeners(200L)).hasSize(1);
        assertThat(storage.getChatListeners(300L)).isEmpty();
    }

    @Test
    void getChatListeners_returnsEmptyForUnknownChat() {
        assertThat(storage.getChatListeners(999L)).isEmpty();
    }

    // ── getAllListeners ───────────────────────────────────────────────────────

    @Test
    void getAllListeners_returnsAllActiveAcrossChats() {
        storage.saveListener(activeListener(1L, 100L));
        storage.saveListener(activeListener(2L, 200L));
        storage.saveListener(activeListener(3L, 300L));

        List<Listener> result = storage.getAllListeners();

        assertThat(result).hasSize(3);
        assertThat(result).allMatch(Listener::isActive);
    }

    @Test
    void getAllListeners_excludesInactive() {
        storage.saveListener(activeListener(1L, 100L));
        storage.saveListener(inactiveListener(2L, 200L));

        assertThat(storage.getAllListeners()).hasSize(1);
    }

    @Test
    void getAllListeners_returnsEmptyWhenNone() {
        assertThat(storage.getAllListeners()).isEmpty();
    }

    // ── deleteListener ────────────────────────────────────────────────────────

    @Test
    void deleteListener_softDeletesSetsActiveFalse() {
        Listener saved = storage.saveListener(activeListener(1L, 100L));

        Listener deleted = storage.deleteListener(saved.getId(), 100L);

        assertThat(deleted.isActive()).isFalse();
    }

    @Test
    void deleteListener_listenerNoLongerReturnedByGetChat() {
        Listener saved = storage.saveListener(activeListener(1L, 100L));

        storage.deleteListener(saved.getId(), 100L);

        assertThat(storage.getChatListeners(100L)).isEmpty();
    }

    @Test
    void deleteListener_listenerNoLongerReturnedByGetAll() {
        Listener saved = storage.saveListener(activeListener(1L, 100L));

        storage.deleteListener(saved.getId(), 100L);

        assertThat(storage.getAllListeners()).isEmpty();
    }

    @Test
    void deleteListener_returnsListenerWithCorrectId() {
        Listener saved = storage.saveListener(activeListener(1L, 100L));

        Listener result = storage.deleteListener(saved.getId(), 100L);

        assertThat(result.getId()).isEqualTo(saved.getId());
        assertThat(result.getUserId()).isEqualTo(1L);
    }


    @Test
    void deleteListener_throwsSecurityExceptionForWrongChatId() {
        Listener saved = storage.saveListener(activeListener(1L, 100L));

        assertThatThrownBy(() -> storage.deleteListener(saved.getId(), 999L))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void deleteListener_listenerStillExistsAfterSecurityFailure() {
        Listener saved = storage.saveListener(activeListener(1L, 100L));

        try {
            storage.deleteListener(saved.getId(), 999L);
        } catch (SecurityException ignored) {
        }

        assertThat(storage.getChatListeners(100L)).hasSize(1);
    }

    @Test
    void deleteListener_onlyDeletesTargetedListener() {
        Listener first = storage.saveListener(activeListener(1L, 100L));
        Listener second = storage.saveListener(activeListener(2L, 100L));

        storage.deleteListener(first.getId(), 100L);

        List<Listener> remaining = storage.getChatListeners(100L);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getId()).isEqualTo(second.getId());
    }
}
