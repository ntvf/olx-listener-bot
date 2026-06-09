package io.chatbots.olx.storage;

import io.chatbots.olx.OlxTelegramBot;
import io.chatbots.olx.storage.entity.Listener;
import io.chatbots.olx.storage.entity.ListenerOfferHash;
import io.chatbots.olx.storage.impl.JpaListenerStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest
@Testcontainers
class HashCleanupServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @MockitoBean
    OlxTelegramBot olxTelegramBot;
    @MockitoBean
    TelegramClient telegramClient;
    @Autowired
    HashCleanupService hashCleanupService;
    @Autowired
    ListenerOfferHashRepository hashRepository;
    @Autowired
    ListenerJpaRepository listenerRepository;
    private Listener savedListener;

    @DynamicPropertySource
    static void configureDs(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.xml");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("bot.token", () -> "test-token");
        r.add("bot.name", () -> "test-bot");
    }

    @BeforeEach
    void setUp() {
        hashRepository.deleteAll();
        listenerRepository.deleteAll();
        savedListener = listenerRepository.save(Listener.builder()
                .userId(1L).chatId(100L)
                .url("https://www.olx.pl/q-test/")
                .updated(new Date())
                .active(true)
                .build());
    }

    @Test
    void removeExpiredHashes_deletesOnlyHashesOlderThanOneYear() {
        long listenerId = savedListener.getId();
        hashRepository.save(new ListenerOfferHash(listenerId, "oldhash",
                Instant.now().minus(400, ChronoUnit.DAYS)));
        hashRepository.save(new ListenerOfferHash(listenerId, "recenthash",
                Instant.now().minus(180, ChronoUnit.DAYS)));

        hashCleanupService.removeExpiredHashes();

        Set<String> remaining = hashRepository.findHashesByListenerId(listenerId);
        assertThat(remaining).containsOnly("recenthash");
        assertThat(remaining).doesNotContain("oldhash");
    }

    @Test
    void removeExpiredHashes_keepsHashesExactlyAtBoundary() {
        long listenerId = savedListener.getId();
        hashRepository.save(new ListenerOfferHash(listenerId, "boundary",
                Instant.now().minus(364, ChronoUnit.DAYS)));

        hashCleanupService.removeExpiredHashes();

        assertThat(hashRepository.findHashesByListenerId(listenerId)).containsOnly("boundary");
    }

    @Test
    void removeExpiredHashes_returnsZeroWhenNothingExpired() {
        long listenerId = savedListener.getId();
        hashRepository.save(new ListenerOfferHash(listenerId, "fresh",
                Instant.now().minus(30, ChronoUnit.DAYS)));

        hashCleanupService.removeExpiredHashes();

        assertThat(hashRepository.findHashesByListenerId(listenerId)).containsOnly("fresh");
    }

    @Test
    void removeExpiredHashes_worksOnEmptyTable() {
        hashCleanupService.removeExpiredHashes();
        assertThat(hashRepository.findAll()).isEmpty();
    }

    @Test
    void deleteListener_removesHashesButKeepsListenerRecord() {
        long listenerId = savedListener.getId();
        hashRepository.save(new ListenerOfferHash(listenerId, "hash1", Instant.now()));
        hashRepository.save(new ListenerOfferHash(listenerId, "hash2", Instant.now()));

        JpaListenerStorage storage = new JpaListenerStorage(listenerRepository, hashRepository);
        storage.deleteListener(listenerId, 100L);

        assertThat(hashRepository.findHashesByListenerId(listenerId)).isEmpty();
        assertThat(listenerRepository.findById(listenerId)).isPresent();
        assertThat(listenerRepository.findById(listenerId).get().isActive()).isFalse();
    }
}
